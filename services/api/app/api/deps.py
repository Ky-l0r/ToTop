"""公共依赖：数据库会话、当前登录用户、分页参数。

端点统一从这里取依赖，保证各处鉴权与分页行为完全一致：

- `DbSession`：请求级数据库会话（**不自动提交**，由端点 / 服务层显式 `commit`）；
- `CurrentUser`：已登录且账号可用的用户；
- `PageParams`：`page` / `size` 分页参数（`page` 从 1 开始）。
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Annotated

from fastapi import Depends, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ErrorCode, UnauthorizedError
from app.core.rate_limit import RateLimit
from app.core.security import decode_token
from app.db.session import get_db as _session_get_db
from app.models.enums import UserStatus
from app.models.user import User

__all__ = [
    "CurrentUser",
    "DbSession",
    "PageParams",
    "Pagination",
    "bearer_scheme",
    "get_current_active_user",
    "get_current_user",
    "get_db",
    "ip_rate_limit",
]

bearer_scheme = HTTPBearer(
    auto_error=False,
    scheme_name="BearerToken",
    description="放在请求头 `Authorization: Bearer <access_token>`",
)


async def get_db() -> AsyncIterator[AsyncSession]:
    """转发 `app.db.session.get_db`，统一数据库依赖的导入位置。"""
    async for session in _session_get_db():
        yield session


DbSession = Annotated[AsyncSession, Depends(get_db)]


async def get_current_user(
    request: Request,
    db: DbSession,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)] = None,
) -> User:
    """解析访问令牌并返回当前用户。

    :raises UnauthorizedError: 未携带令牌（40100）、令牌过期（40101）、令牌无效或用户不存在（40102）
    """
    if credentials is None or not credentials.credentials.strip():
        raise UnauthorizedError("请先登录", code=ErrorCode.UNAUTHORIZED)

    payload = decode_token(credentials.credentials.strip(), "access")
    user = await db.scalar(select(User).where(User.id == str(payload["sub"])))
    if user is None:
        # 令牌本身合法但用户已被删除：按令牌无效处理，避免暴露内部状态
        raise UnauthorizedError("登录状态无效，请重新登录", code=ErrorCode.TOKEN_INVALID)

    request.state.user_id = user.id
    return user


async def get_current_active_user(user: Annotated[User, Depends(get_current_user)]) -> User:
    """在 `get_current_user` 基础上校验账号状态（禁用账号一律拒绝）。"""
    if user.status != UserStatus.ACTIVE:
        raise UnauthorizedError("账号已被禁用，请联系客服", code=ErrorCode.ACCOUNT_DISABLED)
    return user


CurrentUser = Annotated[User, Depends(get_current_active_user)]
"""端点里用 `user: CurrentUser` 即可拿到已登录用户。"""


class Pagination:
    """分页参数容器（由 `pagination_params` 依赖构造）。"""

    __slots__ = ("page", "size")

    def __init__(self, page: int = 1, size: int = 20) -> None:
        self.page = page
        self.size = size

    @property
    def offset(self) -> int:
        """SQL `OFFSET`。"""
        return (self.page - 1) * self.size

    @property
    def limit(self) -> int:
        """SQL `LIMIT`。"""
        return self.size

    def __repr__(self) -> str:  # pragma: no cover —— 仅调试用
        return f"Pagination(page={self.page}, size={self.size})"


def pagination_params(
    page: Annotated[int, Query(ge=1, description="页码，从 1 开始")] = 1,
    size: Annotated[int, Query(ge=1, le=100, description="每页条数，1~100")] = 20,
) -> Pagination:
    """FastAPI 依赖：解析 `page` / `size`。

    这里刻意写成**函数**而不是「类依赖」：本模块启用了
    `from __future__ import annotations`，类的 `__init__` 注解是字符串，
    FastAPI 分析类依赖时拿不到 `__globals__`，会把 `Annotated[...]` 当成 ForwardRef，
    运行期抛 `PydanticUserError: not fully defined`（实测会 500）。
    """
    return Pagination(page=page, size=size)


PageParams = Annotated[Pagination, Depends(pagination_params)]


def ip_rate_limit(
    scope: str,
    limit: int,
    window_seconds: int = 60,
    message: str | None = None,
) -> Callable[[Request], Awaitable[None]]:
    """构造一个「按客户端 IP 限流」的函数依赖。

    为什么包一层：`app.core.rate_limit.RateLimit` 是**类实例**依赖，而本模块处于
    `from __future__ import annotations` 之下，FastAPI 对实例做签名分析时拿不到
    `__globals__`，会误把 `request: Request` 当成必填的 query 参数（返回 40001）。
    包成普通函数后注解可被正常解析。

    用法：`dependencies=[Depends(ip_rate_limit("sms_send", 5, 60))]`
    """
    limiter = RateLimit(scope, limit, window_seconds, message)

    async def _dependency(request: Request) -> None:
        await limiter(request)

    return _dependency
