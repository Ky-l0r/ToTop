"""认证相关 Schema。"""

from __future__ import annotations

from typing import Annotated

from pydantic import Field, StringConstraints, field_validator

from app.models.enums import SmsScene
from app.schemas.common import Schema
from app.schemas.user import UserOut

PhoneStr = Annotated[
    str,
    StringConstraints(pattern=r"^1[3-9]\d{9}$"),
]
"""中国大陆手机号（11 位）。"""

PasswordStr = Annotated[
    str,
    StringConstraints(min_length=6, max_length=64),
]
"""密码：6~64 位（bcrypt 上限为 72 字节，此处更保守）。"""

SmsCodeStr = Annotated[
    str,
    StringConstraints(pattern=r"^\d{4,8}$"),
]
"""短信验证码：4~8 位数字。"""


class SmsSendRequest(Schema):
    """发送短信验证码请求。"""

    phone: PhoneStr = Field(..., description="手机号，如 13800008888", examples=["13800008888"])
    scene: SmsScene = Field(SmsScene.REGISTER, description="场景：register / login / reset")


class SmsSendResult(Schema):
    """发送短信验证码结果。"""

    sent: bool = Field(True, description="是否已发送")
    dev_code: str | None = Field(
        None,
        description="开发模式回传的验证码（仅当 SMS_DEV_RETURN_CODE=true 时出现，生产为 null）",
    )
    expires_in: int = Field(300, description="有效期（秒）")


class RegisterRequest(Schema):
    """注册请求。"""

    phone: PhoneStr = Field(..., description="手机号")
    sms_code: SmsCodeStr = Field(..., description="短信验证码")
    password: PasswordStr | None = Field(None, description="密码，可选；不传则仅支持验证码登录")
    nickname: str | None = Field(None, max_length=32, description="昵称，可选")


class LoginPasswordRequest(Schema):
    """密码登录请求。"""

    phone: PhoneStr = Field(..., description="手机号")
    password: PasswordStr = Field(..., description="密码")


class LoginSmsRequest(Schema):
    """验证码登录请求。"""

    phone: PhoneStr = Field(..., description="手机号")
    sms_code: SmsCodeStr = Field(..., description="短信验证码")


class WechatLoginRequest(Schema):
    """微信小程序一键登录请求。"""

    code: str = Field(..., min_length=1, description="wx.login 返回的临时登录凭证")
    nickname: str | None = Field(None, max_length=64, description="昵称，可选；不传时用默认昵称")
    avatar_url: str | None = Field(None, max_length=512, description="头像地址，可选")


class RefreshRequest(Schema):
    """刷新令牌请求。"""

    refresh_token: str = Field(..., min_length=10, description="登录时下发的 refresh_token")


class SetPasswordRequest(Schema):
    """设置 / 修改密码请求（需登录）。"""

    old_password: PasswordStr | None = Field(None, description="原密码；首次设置密码时可不传")
    new_password: PasswordStr = Field(..., description="新密码，6~64 位")

    @field_validator("new_password")
    @classmethod
    def _not_same(cls, value: str, info: object) -> str:
        """新旧密码不允许相同。"""
        data = getattr(info, "data", {}) or {}
        if data.get("old_password") and data["old_password"] == value:
            raise ValueError("新密码不能与原密码相同")
        return value


class TokenPair(Schema):
    """令牌对。"""

    access_token: str = Field(..., description="访问令牌（Authorization: Bearer <token>）")
    refresh_token: str = Field(..., description="刷新令牌")
    token_type: str = Field("bearer", description="令牌类型")
    expires_in: int = Field(..., description="访问令牌有效期（秒）")


class AuthResult(TokenPair):
    """登录 / 注册结果。"""

    user: UserOut = Field(..., description="用户信息")
