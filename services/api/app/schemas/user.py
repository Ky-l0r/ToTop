"""用户相关 Schema。"""

from __future__ import annotations

from pydantic import Field, field_serializer, field_validator

from app.core.utils import mask_phone
from app.schemas.common import Schema, UtcDatetime


class UserOut(Schema):
    """用户公开信息（手机号一律脱敏）。"""

    id: str = Field(..., description="用户 ID")
    phone: str = Field(..., description="脱敏手机号，如 138****8888")
    nickname: str = Field("", description="昵称")
    avatar_url: str = Field("", description="头像地址")
    credits: int = Field(0, description="点数余额")
    invite_code: str = Field("", description="邀请码")
    created_at: UtcDatetime = Field(..., description="注册时间")

    @field_validator("phone", mode="before")
    @classmethod
    def _mask_phone(cls, value: object) -> str:
        """出参统一脱敏，避免任何接口意外泄露明文手机号。"""
        return mask_phone(str(value) if value is not None else "")

    @field_serializer("nickname", "avatar_url")
    def _none_to_empty(self, value: str | None) -> str:
        """数据库里的可空字符串统一输出为空串，方便前端直接渲染。"""
        return value or ""


class UserUpdate(Schema):
    """修改个人资料请求。"""

    nickname: str | None = Field(None, max_length=32, description="昵称，1~32 字")
    avatar_url: str | None = Field(None, max_length=512, description="头像 URL")

    @field_validator("nickname", "avatar_url")
    @classmethod
    def _strip_blank(cls, value: str | None) -> str | None:
        """空串视为未传。"""
        if value is None:
            return None
        value = value.strip()
        return value or None
