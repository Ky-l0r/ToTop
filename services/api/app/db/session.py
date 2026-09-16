"""数据库层：异步 engine、sessionmaker、FastAPI 依赖与建表工具。"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import BASE_DIR, settings

__all__ = [
    "AsyncSessionLocal",
    "engine",
    "get_db",
    "init_models",
    "ping",
    "session_scope",
]


def _normalize_database_url(url: str) -> str:
    """把 SQLite 的相对路径解析为相对 `services/api` 的绝对路径。

    这样无论从哪个工作目录启动 uvicorn，`./dev.db` 都落在 `services/api/dev.db`。
    """
    prefix = "sqlite+aiosqlite:///"
    if not url.startswith(prefix):
        return url
    raw = url[len(prefix) :]
    if not raw or raw == ":memory:":
        return url
    path = Path(raw)
    if not path.is_absolute():
        path = (BASE_DIR / raw).resolve()
    return f"{prefix}{path.as_posix()}"


DATABASE_URL = _normalize_database_url(settings.database_url)


def _create_engine() -> AsyncEngine:
    """按数据库类型创建异步引擎。"""
    kwargs: dict[str, object] = {
        "echo": settings.db_echo,
        "pool_pre_ping": True,
        "future": True,
    }
    if settings.is_sqlite:
        # SQLite 不需要连接池预检，且需要通过 connect_args 控制锁等待时间
        kwargs.pop("pool_pre_ping", None)
        kwargs["connect_args"] = {"timeout": 30}
    else:
        kwargs["pool_size"] = 10
        kwargs["max_overflow"] = 20
    return create_async_engine(DATABASE_URL, **kwargs)


engine: AsyncEngine = _create_engine()

if settings.is_sqlite:

    @event.listens_for(engine.sync_engine, "connect")
    def _sqlite_pragmas(dbapi_connection: object, _record: object) -> None:
        """SQLite 连接建立时设置 WAL 与外键约束（提升并发读写体验）。"""
        cursor = dbapi_connection.cursor()  # type: ignore[attr-defined]
        try:
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.execute("PRAGMA busy_timeout=5000")
        finally:
            cursor.close()


AsyncSessionLocal: async_sessionmaker[AsyncSession] = async_sessionmaker(
    bind=engine,
    expire_on_commit=False,
    autoflush=False,
    class_=AsyncSession,
)


async def get_db() -> AsyncIterator[AsyncSession]:
    """FastAPI 依赖：每个请求一个会话，请求结束自动关闭。

    注意：这里**不自动提交**，由端点/服务层显式 `commit`，保证扣费等操作的事务边界清晰。
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    """后台任务用的事务作用域：自动提交 / 回滚 / 关闭。"""
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


async def init_models() -> None:
    """按 ORM 元数据建表（开发环境使用；生产请用 alembic 迁移）。"""
    from app.db.base import Base, import_all_models

    import_all_models()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


async def ping() -> bool:
    """探测数据库连通性，供 `/health` 使用。"""
    try:
        async with engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:  # noqa: BLE001 —— 健康检查只关心结果
        return False


async def dispose_engine() -> None:
    """关闭连接池（应用退出时调用）。"""
    await engine.dispose()
