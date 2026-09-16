"""认证端点：短信验证码、注册、密码/验证码登录、刷新令牌、设置密码。

约定（与 `docs/02-API契约.md` 第 2 节一致）：

- 注册成功自动赠送 `settings.register_bonus_credits` 点数，并写一条 `reason=register_bonus` 的账本（幂等键 `register:{user_id}`）；
- 手机号不存在时登录接口返回 **40014**，前端据此自动转为注册流程；
- 刷新令牌只存 SHA-256 哈希，刷新时**旋转**（旧令牌立即作废）。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import CurrentUser, DbSession, ip_rate_limit
from app.core.config import settings
from app.core.errors import ErrorCode, ParamError, UnauthorizedError
from app.core.response import EnvelopeRoute
from app.core.security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    hash_password,
    hash_token,
    token_expires_at,
    verify_password,
)
from app.core.utils import gen_invite_code, new_id, utcnow
from app.models.enums import CreditReason, SmsScene
from app.models.user import RefreshToken, User
from app.schemas.auth import (
    AuthResult,
    LoginPasswordRequest,
    LoginSmsRequest,
    RefreshRequest,
    RegisterRequest,
    SetPasswordRequest,
    SmsSendRequest,
    SmsSendResult,
    TokenPair,
)
from app.schemas.common import OkResult
from app.schemas.user import UserOut
from app.services import sms as sms_service
from app.services.credits import add_credits

logger = logging.getLogger("app.api.auth")

router = APIRouter(route_class=EnvelopeRoute, prefix="/auth", tags=["auth"])

# 生成邀请码时的最大重试次数（唯一约束冲突时重试）
_INVITE_CODE_RETRY = 10


@router.post(
    "/sms/send",
    response_model=SmsSendResult,
    summary="发送短信验证码",
    dependencies=[Depends(ip_rate_limit("sms_send", 5, 60))],
)
async def send_sms_code(payload: SmsSendRequest, db: DbSession) -> SmsSendResult:
    """发送验证码；开发模式会在响应里带上 `dev_code` 便于自测。"""
    result = await sms_service.send_code(db, phone=payload.phone, scene=payload.scene)
    await db.commit()
    return result


@router.post(
    "/register",
    response_model=AuthResult,
    summary="手机号注册",
    dependencies=[Depends(ip_rate_limit("register", 20, 60))],
)
async def register(payload: RegisterRequest, db: DbSession, request: Request) -> AuthResult:
    """注册并自动登录；注册成功赠送点数。"""
    if await db.scalar(select(User.id).where(User.phone == payload.phone)) is not None:
        raise ParamError("该手机号已注册，请直接登录", code=ErrorCode.PHONE_ALREADY_REGISTERED)

    # 注册与登录共用一个验证码（前端「验证码登录未注册自动转注册」的流程）
    await sms_service.verify_code_for_scenes(
        db,
        phone=payload.phone,
        scenes=(SmsScene.REGISTER, SmsScene.LOGIN),
        code=payload.sms_code,
    )

    user = User(
        phone=payload.phone,
        password_hash=hash_password(payload.password) if payload.password else None,
        nickname=(payload.nickname or f"用户{payload.phone[-4:]}").strip(),
        avatar_url="",
        invite_code=await _generate_invite_code(db),
        credits=0,
    )
    db.add(user)
    await db.flush()

    if settings.register_bonus_credits > 0:
        change = await add_credits(
            db,
            user_id=user.id,
            delta=settings.register_bonus_credits,
            reason=CreditReason.REGISTER_BONUS,
            idempotency_key=f"register:{user.id}",
            ref_type="user",
            ref_id=user.id,
            remark="注册赠送点数",
        )
        user.credits = change.balance_after

    result = await _issue_tokens(db, user=user, request=request)
    await db.commit()
    logger.info("新用户注册成功：%s（赠送 %s 点）", user.id, settings.register_bonus_credits)
    return result


@router.post(
    "/login/password",
    response_model=AuthResult,
    summary="密码登录",
    dependencies=[Depends(ip_rate_limit("login", 30, 60))],
)
async def login_by_password(payload: LoginPasswordRequest, db: DbSession, request: Request) -> AuthResult:
    """密码登录；手机号不存在返回 40014，未设置密码返回 40016，密码错误返回 40015。"""
    user = await db.scalar(select(User).where(User.phone == payload.phone))
    if user is None:
        raise ParamError("该手机号尚未注册", code=ErrorCode.PHONE_NOT_REGISTERED)
    if not user.password_hash:
        raise ParamError("该账号还未设置密码，请使用验证码登录", code=ErrorCode.PASSWORD_NOT_SET)
    if not verify_password(payload.password, user.password_hash):
        raise ParamError("密码不正确", code=ErrorCode.PASSWORD_INCORRECT)
    _ensure_active(user)

    result = await _issue_tokens(db, user=user, request=request)
    await db.commit()
    return result


@router.post(
    "/login/sms",
    response_model=AuthResult,
    summary="验证码登录",
    dependencies=[Depends(ip_rate_limit("login_sms", 30, 60))],
)
async def login_by_sms(payload: LoginSmsRequest, db: DbSession, request: Request) -> AuthResult:
    """验证码登录；手机号不存在返回 40014（前端据此自动转注册）。

    注意：**先校验手机号是否存在，再校验验证码**，避免「未注册」被误报成「验证码错误」，
    也保证这个接口不会误消费掉用户手上那个准备用于注册的验证码。
    """
    user = await db.scalar(select(User).where(User.phone == payload.phone))
    if user is None:
        raise ParamError("该手机号尚未注册", code=ErrorCode.PHONE_NOT_REGISTERED)

    await sms_service.verify_code_for_scenes(
        db,
        phone=payload.phone,
        scenes=(SmsScene.LOGIN, SmsScene.REGISTER),
        code=payload.sms_code,
    )
    _ensure_active(user)

    result = await _issue_tokens(db, user=user, request=request)
    await db.commit()
    return result


@router.post("/refresh", response_model=TokenPair, summary="刷新访问令牌")
async def refresh_token(payload: RefreshRequest, db: DbSession, request: Request) -> TokenPair:
    """用 refresh_token 换一对新令牌（旧的 refresh_token 立即作废）。"""
    # 先验签，再查库：签名不对的令牌无需访问数据库
    decode_token(payload.refresh_token, "refresh")

    record = await db.scalar(
        select(RefreshToken).where(RefreshToken.token_hash == hash_token(payload.refresh_token))
    )
    if record is None or record.revoked:
        raise UnauthorizedError("登录已失效，请重新登录", code=ErrorCode.REFRESH_TOKEN_REVOKED)
    if record.expires_at <= utcnow():
        raise UnauthorizedError("登录已过期，请重新登录", code=ErrorCode.TOKEN_EXPIRED)

    user = await db.get(User, record.user_id)
    if user is None:
        raise UnauthorizedError("登录状态无效，请重新登录", code=ErrorCode.TOKEN_INVALID)
    _ensure_active(user)

    record.revoked = True  # 旋转：旧刷新令牌一次性使用
    access = create_access_token(user.id)
    new_refresh = _new_refresh_token(user.id)
    db.add(_build_refresh_record(user_id=user.id, token=new_refresh, request=request))
    await db.commit()

    return TokenPair(
        access_token=access,
        refresh_token=new_refresh,
        expires_in=settings.access_token_expire_minutes * 60,
    )


@router.post("/password", response_model=OkResult, summary="设置 / 修改密码")
async def set_password(payload: SetPasswordRequest, user: CurrentUser, db: DbSession) -> OkResult:
    """登录后设置或修改密码；修改成功后作废该用户全部刷新令牌（其它设备需重新登录）。"""
    if user.password_hash:
        if not payload.old_password:
            raise ParamError("请输入原密码", code=ErrorCode.PARAM_MISSING)
        if not verify_password(payload.old_password, user.password_hash):
            raise ParamError("原密码不正确", code=ErrorCode.PASSWORD_INCORRECT)

    user.password_hash = hash_password(payload.new_password)
    await db.execute(
        update(RefreshToken)
        .where(RefreshToken.user_id == user.id, RefreshToken.revoked.is_(False))
        .values(revoked=True)
    )
    await db.commit()
    logger.info("用户 %s 更新了密码", user.id)
    return OkResult(ok=True)


# ---------------------------------------------------------------------- 内部工具


def _ensure_active(user: User) -> None:
    """账号被禁用时拒绝登录。"""
    if not user.is_active:
        raise UnauthorizedError("账号已被禁用，请联系客服", code=ErrorCode.ACCOUNT_DISABLED)


async def _issue_tokens(db: AsyncSession, *, user: User, request: Request) -> AuthResult:
    """签发访问令牌 + 刷新令牌，并把刷新令牌哈希落库。"""
    access = create_access_token(user.id)
    refresh = _new_refresh_token(user.id)
    db.add(_build_refresh_record(user_id=user.id, token=refresh, request=request))
    return AuthResult(
        access_token=access,
        refresh_token=refresh,
        expires_in=settings.access_token_expire_minutes * 60,
        user=UserOut.model_validate(user),
    )


def _new_refresh_token(user_id: str) -> str:
    """签发刷新令牌。

    **必须带 `jti`**：JWT 的 `iat` / `exp` 精度是秒，同一秒内为同一用户重复签发
    （注册紧接着刷新、或并发刷新）会得到完全相同的令牌串，进而撞上
    `refresh_tokens.token_hash` 的唯一约束。加入随机 `jti` 后每次都唯一。
    """
    return create_refresh_token(user_id, jti=new_id())


def _build_refresh_record(*, user_id: str, token: str, request: Request) -> RefreshToken:
    """构造刷新令牌记录（只存哈希）。"""
    return RefreshToken(
        user_id=user_id,
        token_hash=hash_token(token),
        expires_at=token_expires_at(token),
        user_agent=(request.headers.get("user-agent") or "")[:500],
    )


async def _generate_invite_code(db: AsyncSession) -> str:
    """生成唯一邀请码（冲突则重试若干次）。"""
    for _ in range(_INVITE_CODE_RETRY):
        code = gen_invite_code()
        if await db.scalar(select(User.id).where(User.invite_code == code)) is None:
            return code
    raise ParamError("邀请码生成失败，请稍后重试", code=ErrorCode.INTERNAL_ERROR)
