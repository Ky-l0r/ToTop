"""通用工具：时间、ID、编号生成等。

时间统一约定：**数据库中一律存 UTC 的 naive datetime**（SQLite 与 PostgreSQL 行为一致），
对外输出时通过 `to_iso_z()` 补上 `Z` 后缀（见 `app.schemas.common.UtcDatetime`）。
"""

from __future__ import annotations

import secrets
import string
import uuid
from datetime import date, datetime, timezone

# 无歧义字符集（去掉 0/O、1/I/L），用于邀请码等需要人工念读的场景
_INVITE_ALPHABET = "ACDEFGHJKLMNPQRTUVWXY34679"


def utcnow() -> datetime:
    """当前 UTC 时间（naive，便于跨 SQLite/PostgreSQL 统一存储）。"""
    return datetime.now(timezone.utc).replace(tzinfo=None)


def to_iso_z(value: datetime | None) -> str | None:
    """把（通常是 naive UTC 的）datetime 序列化成带 `Z` 的 ISO 8601 字符串。"""
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    else:
        value = value.astimezone(timezone.utc)
    return value.isoformat().replace("+00:00", "Z")


def to_date_str(value: date | datetime | None) -> str | None:
    """把 date/datetime 序列化成 `YYYY-MM-DD`。"""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date().isoformat()
    return value.isoformat()


def new_id() -> str:
    """生成 32 位十六进制字符串主键（UUID4，无连字符，便于索引）。"""
    return uuid.uuid4().hex


def gen_order_no(prefix: str = "TT") -> str:
    """生成订单号：`TT` + UTC 时间戳 + 6 位随机串，全局唯一且可读。"""
    stamp = utcnow().strftime("%Y%m%d%H%M%S")
    suffix = "".join(secrets.choice(string.digits) for _ in range(6))
    return f"{prefix}{stamp}{suffix}"


def gen_invite_code(length: int = 6) -> str:
    """生成用户邀请码（无歧义大写字母 + 数字）。"""
    return "".join(secrets.choice(_INVITE_ALPHABET) for _ in range(length))


def gen_sms_code(length: int = 6) -> str:
    """生成纯数字短信验证码。"""
    return "".join(secrets.choice(string.digits) for _ in range(length))


def mask_phone(phone: str | None) -> str:
    """手机号脱敏：`13812348888 -> 138****8888`。

    非 11 位手机号按通用规则处理（保留前 3 后 4），空值原样返回。
    """
    if not phone:
        return phone or ""
    digits = phone.strip()
    if len(digits) < 7:
        return digits
    return f"{digits[:3]}****{digits[-4:]}"
