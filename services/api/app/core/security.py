"""安全相关：密码哈希（bcrypt）、JWT 签发与校验、手机号脱敏。"""

from __future__ import annotations

import hashlib
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

import bcrypt
import jwt

from app.core.config import settings
from app.core.errors import ErrorCode, UnauthorizedError
from app.core.utils import mask_phone, utcnow

TokenType = Literal["access", "refresh"]

# bcrypt 只处理前 72 字节，超长密码需在入口处拦截（见 schemas.auth.PasswordField）
BCRYPT_MAX_BYTES = 72


def hash_password(password: str) -> str:
    """用 bcrypt 生成密码哈希。"""
    raw = password.encode("utf-8")
    if len(raw) > BCRYPT_MAX_BYTES:
        raise ValueError("密码过长（bcrypt 最多支持 72 字节）")
    return bcrypt.hashpw(raw, bcrypt.gensalt(rounds=12)).decode("ascii")


def verify_password(password: str, password_hash: str | None) -> bool:
    """校验密码；哈希为空或格式非法时返回 False。"""
    if not password_hash:
        return False
    try:
        return bcrypt.checkpw(password.encode("utf-8")[:BCRYPT_MAX_BYTES], password_hash.encode("ascii"))
    except (ValueError, TypeError):
        return False


def create_token(
    subject: str,
    token_type: TokenType = "access",
    *,
    expires_delta: timedelta | None = None,
    extra: dict[str, Any] | None = None,
) -> str:
    """签发 JWT。

    :param subject: 用户 ID
    :param token_type: `access` 或 `refresh`
    :param expires_delta: 有效期；不传则按配置中的默认值（access 24h / refresh 14d）
    """
    now = utcnow()
    if expires_delta is None:
        expires_delta = (
            timedelta(minutes=settings.access_token_expire_minutes)
            if token_type == "access"
            else timedelta(days=settings.refresh_token_expire_days)
        )
    payload: dict[str, Any] = {
        "sub": subject,
        "type": token_type,
        "iat": int(now.timestamp()),
        "exp": int((now + expires_delta).timestamp()),
    }
    if extra:
        payload.update(extra)
    return jwt.encode(payload, settings.secret_key, algorithm=settings.jwt_algorithm)


def create_access_token(user_id: str, **extra: Any) -> str:
    """签发访问令牌。"""
    return create_token(user_id, "access", extra=extra or None)


def create_refresh_token(user_id: str, **extra: Any) -> str:
    """签发刷新令牌。"""
    return create_token(user_id, "refresh", extra=extra or None)


def decode_token(token: str, expected_type: TokenType | None = None) -> dict[str, Any]:
    """解码并校验 JWT。

    :raises UnauthorizedError: 令牌过期（40101）或无效 / 类型不符（40102）
    """
    try:
        payload: dict[str, Any] = jwt.decode(token, settings.secret_key, algorithms=[settings.jwt_algorithm])
    except jwt.ExpiredSignatureError as exc:
        raise UnauthorizedError("登录已过期，请重新登录", code=ErrorCode.TOKEN_EXPIRED) from exc
    except jwt.PyJWTError as exc:
        raise UnauthorizedError("令牌无效", code=ErrorCode.TOKEN_INVALID) from exc

    if expected_type is not None and payload.get("type") != expected_type:
        raise UnauthorizedError("令牌类型不正确", code=ErrorCode.TOKEN_INVALID)
    if not payload.get("sub"):
        raise UnauthorizedError("令牌缺少主体信息", code=ErrorCode.TOKEN_INVALID)
    return payload


def hash_token(token: str) -> str:
    """对刷新令牌做 SHA-256，落库只存哈希，避免库被读走后可直接冒用。"""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def token_expires_at(token: str) -> datetime:
    """解析令牌中的 exp 并返回 naive UTC datetime（解析失败时回退为 14 天后）。"""
    try:
        payload = jwt.decode(token, settings.secret_key, algorithms=[settings.jwt_algorithm], options={"verify_exp": False})
        exp = payload.get("exp")
        if exp:
            return datetime.fromtimestamp(int(exp), tz=timezone.utc).replace(tzinfo=None)
    except jwt.PyJWTError:
        pass
    return utcnow() + timedelta(days=settings.refresh_token_expire_days)


__all__ = [
    "BCRYPT_MAX_BYTES",
    "create_access_token",
    "create_refresh_token",
    "create_token",
    "decode_token",
    "hash_password",
    "hash_token",
    "mask_phone",
    "token_expires_at",
    "verify_password",
]
