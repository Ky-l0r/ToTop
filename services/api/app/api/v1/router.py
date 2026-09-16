"""汇总 `/api/v1` 下的全部子路由。

`app.main` 只需 `app.include_router(api_router, prefix=settings.api_v1_prefix)`。
本路由与全部子路由都使用 `EnvelopeRoute`，因此端点返回的业务对象会被自动包成
`{"code": 0, "message": "ok", "data": {...}}`；错误响应由 `app.core.errors` 的异常处理器产出。
"""

from __future__ import annotations

from fastapi import APIRouter

from app.api.v1.endpoints import analyses, auth, credits, exams, meta, orders, trends, uploads, users
from app.core.response import EnvelopeRoute

api_router = APIRouter(route_class=EnvelopeRoute)

api_router.include_router(meta.router)
api_router.include_router(auth.router)
api_router.include_router(users.router)
api_router.include_router(credits.router)
api_router.include_router(orders.router)
api_router.include_router(exams.router)
api_router.include_router(uploads.router)
api_router.include_router(analyses.router)
api_router.include_router(trends.router)

__all__ = ["api_router"]
