"""认证端点：短信验证码、注册、密码/验证码登录、微信一键登录、刷新令牌、设置密码。

约定（与 `docs/02-API契约.md` 第 2 节一致）：

- 注册成功自动赠送 `settings.register_bonus_credits` 点数，并写一条 `reason=register_bonus` 的账本（幂等键 `register:{user_id}`）；
- 手机号不存在时登录接口返回 **40014**，前端据此自动转为注册流程；
- 微信小程序一键登录（`POST /auth/wechat/login`）按 `openid` 识别用户，首次登录自动建号，
  响应结构与手机号登录完全一致（复用 `_issue_tokens`）；
- 刷新令牌只存 SHA-256 哈希，刷新时**旋转**（旧令牌立即作废）。
"""

from __future__ import annotations

import hashlib
import logging

import httpx
from fastapi import APIRouter, Depends, Request
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import CurrentUser, DbSession, ip_rate_limit
from app.core.config import settings
from app.core.errors import ErrorCode, ParamError, UnauthorizedError, UpstreamError
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
    WechatLoginRequest,
)
from app.schemas.common import OkResult
from app.schemas.user import UserOut
from app.services import sms as sms_service
from app.services.credits import add_credits

logger = logging.getLogger("app.api.auth")

router = APIRouter(route_class=EnvelopeRoute, prefix="/auth", tags=["auth"])

# 生成邀请码时的最大重试次数（唯一约束冲突时重试）
_INVITE_CODE_RETRY = 10

# 微信 code2Session 接口（POST/GET 均可，这里用 GET 拼 query）
_WECHAT_CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session"
_WECHAT_TIMEOUT_SECONDS = 10.0

# 微信一键登录的默认昵称（前端未传昵称时使用）
_WECHAT_DEFAULT_NICKNAME = "微信用户"

# 日志里最多打印 openid 的前若干位，避免把完整凭据写进日志
_OPENID_LOG_PREFIX = 8


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


@router.post(
    "/wechat/login",
    response_model=AuthResult,
    summary="微信小程序一键登录",
    dependencies=[Depends(ip_rate_limit("wechat_login", 30, 60))],
)
async def login_by_wechat(payload: WechatLoginRequest, db: DbSession, request: Request) -> AuthResult:
    """微信小程序一键登录（**无需鉴权**）：用 `wx.login` 的 code 换 openid，按 openid 认人。

    - 配置了 `WECHAT_APPID` / `WECHAT_SECRET` 时调用微信 `code2Session`；
      未配置时走 dev 模式（用 code 的哈希派生稳定假 openid），保证本地无凭据也能跑通；
    - openid 已存在即直接登录；不存在则建号（`phone=None`）并在**同一事务**内
      赠送 `settings.register_bonus_credits` 点数（账本幂等键 `register:{user_id}`）；
    - 返回值与手机号登录**完全同构**：`{access_token, refresh_token, user}`。
    """
    openid = await _resolve_openid(payload.code)

    user = await db.scalar(select(User).where(User.openid == openid))
    created = user is None
    if user is None:
        user, created = await _create_wechat_user(db, payload=payload, openid=openid)
    _ensure_active(user)

    result = await _issue_tokens(db, user=user, request=request)
    await db.commit()
    logger.info(
        "微信登录成功：用户 %s（%s），openid 前缀 %s",
        user.id,
        "新建账号" if created else "已有账号",
        openid[:_OPENID_LOG_PREFIX],
    )
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


async def _create_wechat_user(
    db: AsyncSession,
    *,
    payload: WechatLoginRequest,
    openid: str,
) -> tuple[User, bool]:
    """按 openid 建号，并在同一事务内赠送注册点数。

    :return: `(用户, 是否本次新建)`；并发首登撞上 openid 唯一约束时，
        回滚本次插入并复用另一个请求刚建好的账号（返回 `False`）。
    """
    user = User(
        phone=None,  # 微信一键登录拿不到手机号
        openid=openid,
        password_hash=None,
        nickname=(payload.nickname or "").strip() or _WECHAT_DEFAULT_NICKNAME,
        avatar_url=(payload.avatar_url or "").strip(),
        invite_code=await _generate_invite_code(db),
        credits=0,
    )
    db.add(user)
    try:
        await db.flush()
    except IntegrityError:
        # 并发首登：另一个请求已用同一 openid 建号，回滚本次插入后复用已有账号
        await db.rollback()
        existing = await db.scalar(select(User).where(User.openid == openid))
        if existing is None:
            raise
        logger.info("微信首登并发冲突，复用已存在账号 %s", existing.id)
        return existing, False

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

    return user, True


async def _resolve_openid(code: str) -> str:
    """用 `wx.login` 的 code 换取 openid。

    配置了 `WECHAT_APPID` / `WECHAT_SECRET` 时请求微信 `code2Session`；
    二者任一为空即走 dev 模式（`_dev_openid`），保证无凭据环境下也能完整跑通登录链路。

    :raises ParamError: 微信返回 `errcode != 0`（detail 带 `errcode` / `errmsg`）
    :raises UpstreamError: 网络异常或响应无法解析
    """
    appid = settings.wechat_appid.strip()
    secret = settings.wechat_secret.strip()
    if not appid or not secret:
        return _dev_openid(code)

    try:
        async with httpx.AsyncClient(timeout=_WECHAT_TIMEOUT_SECONDS) as client:
            response = await client.get(
                _WECHAT_CODE2SESSION_URL,
                params={
                    "appid": appid,
                    "secret": secret,
                    "js_code": code,
                    "grant_type": "authorization_code",
                },
            )
        response.raise_for_status()
        data = response.json()
    except httpx.HTTPError as exc:
        raise UpstreamError("微信登录服务暂时不可用，请稍后重试", detail={"error": str(exc)}) from exc
    except ValueError as exc:  # 微信返回了非 JSON（如网关错误页）
        raise UpstreamError("微信登录返回了无法解析的响应", detail={"error": str(exc)}) from exc

    if not isinstance(data, dict):
        raise UpstreamError("微信登录返回了非预期的响应", detail={"body": str(data)[:200]})

    errcode = int(data.get("errcode") or 0)
    errmsg = str(data.get("errmsg") or "")
    if errcode != 0:
        raise ParamError(f"微信登录失败：{errmsg or '未知错误'}", detail={"errcode": errcode, "errmsg": errmsg})

    openid = str(data.get("openid") or "").strip()
    if not openid:
        raise ParamError("微信登录失败：未返回 openid", detail={"errcode": errcode, "errmsg": errmsg})
    return openid


def _dev_openid(code: str) -> str:
    """dev 模式下的假 openid：`dev_` + code 的 SHA-256 前 24 位（同一 code 结果稳定）。"""
    return f"dev_{hashlib.sha256(code.encode('utf-8')).hexdigest()[:24]}"


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
