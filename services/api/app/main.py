"""FastAPI 应用入口。

职责：

1. 创建应用实例（`/docs` 仅开发环境开放）；
2. 注册 CORS、请求追踪（`X-Trace-Id`）与全局限流中间件；
3. 注册统一异常处理器（`app.core.errors`）；
4. 挂载 `/static` 静态目录（本地存储的试卷 / 答题卡图片通过它访问）；
5. 注册 `/api/v1` 下的全部路由（**信封包装**由 `EnvelopeRoute` 完成）；
6. 暴露 `GET /health`（按契约**不包信封**，直接返回 `{status, version, ai_provider, db, db_ok, env}`）。

启动 / 关闭（lifespan）：

- 启动：按 `settings.auto_create_tables` 建表 → 幂等补齐学科字典 → 清理上次进程遗留的
  `pending` / `running` 任务（标记失败并**退还点数**，避免用户白扣）；
- 关闭：取消并等待后台任务 → 关闭 Redis 连接 → 释放数据库连接池。
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select

from app.api.v1.endpoints.meta import ensure_dictionary
from app.api.v1.router import api_router
from app.core.config import settings
from app.core.errors import ErrorCode, register_exception_handlers
from app.core.rate_limit import GLOBAL_SCOPE, client_ip, hit, reset_redis_client
from app.core.utils import utcnow
from app.db.session import dispose_engine, init_models, ping, session_scope
from app.models.analysis import Analysis, ImportTask
from app.models.enums import CreditReason, TaskStatus
from app.schemas.common import HealthResult
from app.services.credits import add_credits
from app.tasks import queue

logger = logging.getLogger("app.main")

# 限流窗口（秒）与全局限流的作用路径
_RATE_LIMIT_WINDOW_SECONDS = 60

# 静态目录必须在挂载前存在（StaticFiles 在导入期检查目录）
settings.storage_dir.mkdir(parents=True, exist_ok=True)


def _configure_logging() -> None:
    """配置日志：应用日志按 debug 开关调整，第三方库只保留警告以上。"""
    if not logging.getLogger().handlers:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s | %(levelname)-7s | %(name)s | %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    logging.getLogger("app").setLevel(logging.DEBUG if settings.debug else logging.INFO)
    # aiosqlite / httpx 等在 DEBUG 级别会产生海量噪音，统一压到 WARNING
    for noisy in ("aiosqlite", "asyncio", "httpcore", "httpx", "urllib3", "multipart"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


async def _fail_interrupted_work() -> None:
    """清理上一次进程遗留的未完成任务。

    进程被强杀（`Ctrl+C`、崩溃、`--reload` 重启）时，内存中的后台协程会消失，
    但数据库里仍留着 `pending` / `running` 的记录。启动时统一收尾：

    - `analyses`：标记 `failed` 并**退还本次扣费**（幂等键 `refund:{analysis_id}`）；
    - `import_tasks`：标记 `failed`，前端轮询会立刻得到终止态而不是一直转圈。
    """
    try:
        async with session_scope() as db:
            analyses = list(
                await db.scalars(
                    select(Analysis).where(Analysis.status.in_((TaskStatus.PENDING, TaskStatus.RUNNING)))
                )
            )
            for analysis in analyses:
                analysis.status = TaskStatus.FAILED
                analysis.error = "服务重启导致分析中断，点数已退还，请重新发起"
                analysis.finished_at = utcnow()
                if analysis.credits_cost > 0:
                    await add_credits(
                        db,
                        user_id=analysis.user_id,
                        delta=int(analysis.credits_cost),
                        reason=CreditReason.REFUND,
                        idempotency_key=f"refund:{analysis.id}",
                        ref_type="analysis",
                        ref_id=analysis.id,
                        remark="服务重启，分析中断退还点数",
                    )

            tasks = list(
                await db.scalars(
                    select(ImportTask).where(ImportTask.status.in_((TaskStatus.PENDING, TaskStatus.RUNNING)))
                )
            )
            for task in tasks:
                task.status = TaskStatus.FAILED
                task.error = "服务重启导致任务中断，请重新上传"
                task.progress = 100
                task.finished_at = utcnow()

            if analyses or tasks:
                logger.warning(
                    "启动清理：%s 个未完成分析、%s 个未完成导入任务已标记为失败", len(analyses), len(tasks)
                )
    except Exception:  # noqa: BLE001 —— 表还没建好时（关闭了 auto_create_tables）不应阻止服务启动
        logger.warning("启动清理未完成（表可能尚不存在），已跳过", exc_info=True)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """应用生命周期：建表 / 字典 / 遗留任务清理 → 运行 → 释放资源。"""
    _configure_logging()

    if settings.auto_create_tables:
        await init_models()
        async with session_scope() as db:
            await ensure_dictionary(db)

    await _fail_interrupted_work()

    logger.info(
        "%s v%s 启动完成 | env=%s | db=%s | ai=%s | storage=%s | 文档 %s",
        settings.app_name,
        settings.app_version,
        settings.env,
        settings.database_backend,
        settings.effective_ai_provider,
        settings.storage_backend,
        "/docs" if settings.is_dev else "已关闭",
    )

    try:
        yield
    finally:
        await queue.shutdown()
        await reset_redis_client()
        await dispose_engine()
        logger.info("服务已关闭")


app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description=(
        "ToTop 考试失分分析后端。除 `/health` 外，全部接口位于 "
        f"`{settings.api_v1_prefix}` 下，响应统一为 `{{code, message, data}}`。"
    ),
    # 注意：这里固定 debug=False —— 若跟随 settings.debug 打开 Starlette 的调试模式，
    # 未捕获异常会直接返回 HTML traceback 而不是统一的 `{code: 50000}` 信封，
    # 破坏客户端契约。开发期需要堆栈请直接看服务端日志（异常处理器里有 logger.exception）。
    debug=False,
    lifespan=lifespan,
    docs_url="/docs" if settings.is_dev else None,
    redoc_url=None,
    openapi_url="/openapi.json" if settings.is_dev else None,
)


# ------------------------------------------------------------------ 中间件
# 注意顺序：后添加的中间件更靠外，因此 CORS 必须在「追踪 + 限流」之后注册，
# 这样限流直接返回的 429 响应也会带上 CORS 头。


@app.middleware("http")
async def trace_and_rate_limit(request: Request, call_next):  # type: ignore[no-untyped-def]
    """写入请求追踪号，并按 IP 做兜底限流。"""
    request.state.trace_id = request.headers.get("x-trace-id") or uuid.uuid4().hex[:16]

    if settings.rate_limit_enabled and request.url.path.startswith(settings.api_v1_prefix):
        allowed, retry_after = await hit(
            GLOBAL_SCOPE,
            client_ip(request),
            settings.rate_limit_per_minute,
            _RATE_LIMIT_WINDOW_SECONDS,
        )
        if not allowed:
            # 中间件里不能抛业务异常（不会走到 ExceptionMiddleware），直接返回统一错误体
            return JSONResponse(
                status_code=429,
                content={
                    "code": ErrorCode.RATE_LIMITED,
                    "message": f"请求过于频繁，请 {retry_after} 秒后再试",
                    "detail": {"retry_after": retry_after},
                    "trace_id": request.state.trace_id,
                },
            )

    response = await call_next(request)
    response.headers.setdefault("X-Trace-Id", request.state.trace_id)
    return response


_cors_allow_all = "*" in settings.cors_origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=not _cors_allow_all,  # 通配来源时不能携带凭证（浏览器规范）
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["X-Trace-Id"],
)


# ------------------------------------------------------------------ 异常处理
register_exception_handlers(app)


# ------------------------------------------------------------------ 静态资源
app.mount(
    settings.static_url_prefix,
    StaticFiles(directory=str(settings.storage_dir)),
    name="static",
)


# ------------------------------------------------------------------ 健康检查
@app.get("/health", response_model=HealthResult, tags=["meta"], summary="健康检查")
async def health() -> HealthResult:
    """服务健康检查（按契约**不包信封**，直接返回对象）。"""
    db_ok = await ping()
    return HealthResult(
        status="ok" if db_ok else "degraded",
        version=settings.app_version,
        ai_provider=settings.effective_ai_provider,
        db=settings.database_backend,
        db_ok=db_ok,
        env=settings.env,
    )


@app.get("/", tags=["meta"], summary="服务信息", include_in_schema=False)
async def root() -> dict[str, object]:
    """根路径：返回服务名与文档地址，便于人工确认服务在跑。"""
    return {
        "app": settings.app_name,
        "version": settings.app_version,
        "api_prefix": settings.api_v1_prefix,
        "docs": "/docs" if settings.is_dev else None,
        "health": "/health",
    }


# ------------------------------------------------------------------ 业务路由
app.include_router(api_router, prefix=settings.api_v1_prefix)
