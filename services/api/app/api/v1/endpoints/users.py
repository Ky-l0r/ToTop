"""用户端点：查看 / 修改个人资料。"""

from __future__ import annotations

from fastapi import APIRouter

from app.api.deps import CurrentUser, DbSession
from app.core.response import EnvelopeRoute
from app.schemas.user import UserOut, UserUpdate

router = APIRouter(route_class=EnvelopeRoute, prefix="/users", tags=["users"])


@router.get("/me", response_model=UserOut, summary="当前用户信息")
async def read_me(user: CurrentUser) -> UserOut:
    """返回当前登录用户（手机号自动脱敏为 `138****8888`）。"""
    return UserOut.model_validate(user)


@router.patch("/me", response_model=UserOut, summary="修改个人资料")
async def update_me(payload: UserUpdate, user: CurrentUser, db: DbSession) -> UserOut:
    """修改昵称 / 头像；未传的字段保持不变（传空串视为不修改）。"""
    if payload.nickname is not None:
        user.nickname = payload.nickname
    if payload.avatar_url is not None:
        user.avatar_url = payload.avatar_url
    await db.commit()
    return UserOut.model_validate(user)
