"""统一错误码、业务异常与全局异常处理器。

响应体固定为：`{"code": <非 0>, "message": "...", "detail": {...} | null}`，
并且 HTTP 状态码与业务码段保持一致（400xx -> 400 / 401xx -> 401 / 402xx -> 402 / 500xx -> 500）。

业务码分段约定：
- `40xxx` 参数 / 业务校验失败
- `401xx` 鉴权、认证、令牌相关
- `402xx` 点数不足
- `404xx` 资源不存在
- `429xx` 触发限流
- `500xx` 服务端异常
"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("app.errors")


class ErrorCode:
    """全部业务错误码常量。"""

    OK = 0

    # 参数 / 业务校验
    PARAM_INVALID = 40001
    PARAM_MISSING = 40002
    SMS_CODE_INVALID = 40010
    SMS_CODE_EXPIRED = 40011
    SMS_SEND_TOO_FREQUENT = 40012
    PHONE_ALREADY_REGISTERED = 40013
    PHONE_NOT_REGISTERED = 40014
    PASSWORD_INCORRECT = 40015
    PASSWORD_NOT_SET = 40016
    AD_EVENT_DUPLICATED = 40020
    AD_WATCH_TOO_SHORT = 40021
    UPLOAD_TOO_LARGE = 40030
    UPLOAD_TYPE_NOT_ALLOWED = 40031
    ORDER_STATUS_INVALID = 40040
    EXAM_HAS_NO_ANSWER_SHEET = 40050

    # 鉴权
    UNAUTHORIZED = 40100
    TOKEN_EXPIRED = 40101
    TOKEN_INVALID = 40102
    REFRESH_TOKEN_REVOKED = 40103
    FORBIDDEN = 40104
    ACCOUNT_DISABLED = 40105

    # 点数
    CREDIT_NOT_ENOUGH = 40200

    # 资源
    NOT_FOUND = 40400

    # 限流
    RATE_LIMITED = 42900

    # 服务端
    INTERNAL_ERROR = 50000
    UPSTREAM_ERROR = 50001
    STORAGE_ERROR = 50002
    DB_ERROR = 50003


# 业务码 -> HTTP 状态码的兜底映射（按百位段推导）
_DEFAULT_HTTP_STATUS: dict[int, int] = {
    400: status.HTTP_400_BAD_REQUEST,
    401: status.HTTP_401_UNAUTHORIZED,
    402: status.HTTP_402_PAYMENT_REQUIRED,
    404: status.HTTP_404_NOT_FOUND,
    429: status.HTTP_429_TOO_MANY_REQUESTS,
    500: status.HTTP_500_INTERNAL_SERVER_ERROR,
}


def http_status_for_code(code: int) -> int:
    """由业务码推导 HTTP 状态码：`40010 -> 400`、`40200 -> 402`、`50001 -> 500`。"""
    if code == ErrorCode.OK:
        return status.HTTP_200_OK
    return _DEFAULT_HTTP_STATUS.get(code // 100, status.HTTP_400_BAD_REQUEST)


class AppError(Exception):
    """所有可预期业务异常的基类。"""

    code: int = ErrorCode.INTERNAL_ERROR
    message: str = "服务异常"
    http_status: int | None = None

    def __init__(
        self,
        message: str | None = None,
        *,
        code: int | None = None,
        detail: Any = None,
        http_status: int | None = None,
    ) -> None:
        self.code = code if code is not None else self.code
        self.message = message if message is not None else self.message
        self.detail = detail
        self.http_status = http_status or self.http_status or http_status_for_code(self.code)
        super().__init__(self.message)

    def to_payload(self) -> dict[str, Any]:
        """转换成统一错误响应体。"""
        return {"code": self.code, "message": self.message, "detail": self.detail}


class ParamError(AppError):
    """参数错误。"""

    code = ErrorCode.PARAM_INVALID
    message = "参数不正确"


class UnauthorizedError(AppError):
    """未登录 / 令牌无效 / 令牌过期。"""

    code = ErrorCode.UNAUTHORIZED
    message = "登录状态无效，请重新登录"


class ForbiddenError(AppError):
    """已登录但无权访问该资源。"""

    code = ErrorCode.FORBIDDEN
    message = "无权访问该资源"


class CreditNotEnoughError(AppError):
    """点数不足（HTTP 402）。"""

    code = ErrorCode.CREDIT_NOT_ENOUGH
    message = "点数不足，请充值或观看广告获取点数"


class NotFoundError(AppError):
    """资源不存在。"""

    code = ErrorCode.NOT_FOUND
    message = "资源不存在"


class RateLimitedError(AppError):
    """触发限流。"""

    code = ErrorCode.RATE_LIMITED
    message = "操作过于频繁，请稍后再试"


class UpstreamError(AppError):
    """上游依赖（AI、短信、存储等）异常。"""

    code = ErrorCode.UPSTREAM_ERROR
    message = "上游服务异常，请稍后重试"


def register_exception_handlers(app: FastAPI) -> None:
    """把各类异常统一注册成 `{code, message, detail}` 响应。"""

    @app.exception_handler(AppError)
    async def _app_error_handler(request: Request, exc: AppError) -> JSONResponse:
        """业务异常：直接按异常自带的 code/HTTP 状态返回。"""
        trace_id = _trace_id(request)
        if exc.code >= ErrorCode.INTERNAL_ERROR:
            logger.warning("业务异常[%s] %s %s -> %s", trace_id, request.method, request.url.path, exc.message)
        return JSONResponse(
            status_code=exc.http_status or http_status_for_code(exc.code),
            content={**exc.to_payload(), "trace_id": trace_id},
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        """Pydantic 请求校验失败：归一到 40001。"""
        detail = [
            {
                "loc": [str(part) for part in err.get("loc", [])],
                "msg": err.get("msg", ""),
                "type": err.get("type", ""),
            }
            for err in exc.errors()
        ]
        first = detail[0]["msg"] if detail else "参数不正确"
        loc = ".".join(detail[0]["loc"]) if detail else ""
        message = f"参数校验失败：{loc} {first}".strip()
        return JSONResponse(
            status_code=status.HTTP_400_BAD_REQUEST,
            content={
                "code": ErrorCode.PARAM_INVALID,
                "message": message,
                "detail": detail,
                "trace_id": _trace_id(request),
            },
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        """Starlette/FastAPI 自带 HTTPException（404、405 等）。"""
        code_map = {
            status.HTTP_401_UNAUTHORIZED: ErrorCode.UNAUTHORIZED,
            status.HTTP_403_FORBIDDEN: ErrorCode.FORBIDDEN,
            status.HTTP_404_NOT_FOUND: ErrorCode.NOT_FOUND,
            status.HTTP_405_METHOD_NOT_ALLOWED: ErrorCode.PARAM_INVALID,
            status.HTTP_429_TOO_MANY_REQUESTS: ErrorCode.RATE_LIMITED,
        }
        code = code_map.get(exc.status_code, ErrorCode.INTERNAL_ERROR if exc.status_code >= 500 else ErrorCode.PARAM_INVALID)
        message = exc.detail if isinstance(exc.detail, str) else "请求失败"
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": code, "message": message, "detail": None, "trace_id": _trace_id(request)},
        )

    @app.exception_handler(Exception)
    async def _unhandled_handler(request: Request, exc: Exception) -> JSONResponse:
        """兜底异常：记录堆栈，对外只暴露 50000。"""
        trace_id = _trace_id(request)
        logger.exception("未捕获异常[%s] %s %s", trace_id, request.method, request.url.path, exc_info=exc)
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={
                "code": ErrorCode.INTERNAL_ERROR,
                "message": "服务器内部错误，请稍后重试",
                "detail": None,
                "trace_id": trace_id,
            },
        )


def _trace_id(request: Request) -> str:
    """取（或生成）本次请求的追踪号，便于日志排查。"""
    trace_id = getattr(request.state, "trace_id", None)
    if not trace_id:
        trace_id = uuid.uuid4().hex[:16]
        request.state.trace_id = trace_id
    return trace_id
