"""用户、短信验证码、刷新令牌。"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.utils import new_id, utcnow
from app.db.base import Base
from app.models.enums import SmsScene, UserStatus, enum_column
from app.models.mixins import TimestampMixin


class User(Base, TimestampMixin):
    """用户。

    两种注册来源共存于同一张表：

    - 手机号注册 / 登录：`phone` 有值、`openid` 为 `None`；
    - 微信小程序一键登录：`openid` 有值、`phone` 为 `None`（拿不到手机号）。

    因此 `phone` 与 `openid` 都可空但各自唯一（SQLite / PostgreSQL 的 UNIQUE 均允许多个 NULL）。

    `phone` 存明文（业务需要按手机号登录/找回），对外输出一律经过 `mask_phone` 脱敏。
    """

    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    phone: Mapped[str | None] = mapped_column(String(20), unique=True, index=True, nullable=True)
    """手机号；微信一键登录创建的用户没有手机号，为 `None`。"""
    openid: Mapped[str | None] = mapped_column(String(64), unique=True, index=True, nullable=True)
    """微信小程序 openid；手机号注册的老用户为 `None`。"""
    password_hash: Mapped[str | None] = mapped_column(String(128), nullable=True)
    nickname: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    avatar_url: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    credits: Mapped[int] = mapped_column(Integer, default=0, server_default="0", nullable=False)
    invite_code: Mapped[str] = mapped_column(String(16), unique=True, index=True, nullable=False)
    status: Mapped[UserStatus] = mapped_column(
        enum_column(UserStatus, 16), default=UserStatus.ACTIVE, nullable=False
    )

    @property
    def is_active(self) -> bool:
        """账号是否可用。"""
        return self.status == UserStatus.ACTIVE


class SmsCode(Base):
    """短信验证码记录。"""

    __tablename__ = "sms_codes"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    phone: Mapped[str] = mapped_column(String(20), index=True, nullable=False)
    code: Mapped[str] = mapped_column(String(8), nullable=False)
    scene: Mapped[SmsScene] = mapped_column(enum_column(SmsScene, 16), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    used: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)


class RefreshToken(Base):
    """刷新令牌（只存 SHA-256 哈希）。"""

    __tablename__ = "refresh_tokens"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0", nullable=False)
    user_agent: Mapped[str] = mapped_column(Text, default="", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)
