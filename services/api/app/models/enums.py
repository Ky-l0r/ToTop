"""ORM 枚举定义。

全部使用 `native_enum=False`（落库为 VARCHAR + Python 层校验），
这样 SQLite 与 PostgreSQL 行为完全一致，也不需要在 PG 里维护 enum 类型。
"""

from __future__ import annotations

from enum import Enum
from typing import Any, TypeVar

from sqlalchemy import Enum as SAEnum

E = TypeVar("E", bound=Enum)


def enum_column(enum_cls: type[E], length: int = 32) -> SAEnum:
    """构造一个跨库通用的枚举列类型（存枚举的 value 字符串）。"""
    return SAEnum(
        enum_cls,
        native_enum=False,
        length=length,
        validate_strings=True,
        values_callable=lambda cls: [member.value for member in cls],  # type: ignore[arg-type]
    )


class UserStatus(str, Enum):
    """用户状态。"""

    ACTIVE = "active"
    DISABLED = "disabled"


class SmsScene(str, Enum):
    """短信场景。"""

    REGISTER = "register"
    LOGIN = "login"
    RESET = "reset"


class CreditReason(str, Enum):
    """点数变动原因。"""

    REGISTER_BONUS = "register_bonus"
    ANALYSIS = "analysis"
    AD_REWARD = "ad_reward"
    PURCHASE = "purchase"
    ADMIN_ADJUST = "admin_adjust"
    REFUND = "refund"


class AdPlatform(str, Enum):
    """广告平台。"""

    WECHAT = "wechat"
    """微信小程序流量主（激励视频）。"""
    CHUANGSHANJIAN = "chuangshanjian"
    GDT = "gdt"
    ADMOB = "admob"
    MOCK = "mock"


class AdType(str, Enum):
    """广告类型（当前只支持激励视频）。"""

    REWARD_VIDEO = "reward_video"


class PayChannel(str, Enum):
    """支付渠道。"""

    WECHAT = "wechat"
    ALIPAY = "alipay"
    APPLE_IAP = "apple_iap"
    MOCK = "mock"


class OrderStatus(str, Enum):
    """订单状态。"""

    PENDING = "pending"
    PAID = "paid"
    FAILED = "failed"
    REFUNDED = "refunded"


class ExamType(str, Enum):
    """考试类型（值即前端展示的中文）。"""

    MONTHLY = "月考"
    MIDTERM = "期中"
    FINAL = "期末"
    MOCK = "模拟"


class SourceType(str, Enum):
    """上传来源类型。"""

    PHOTO = "photo"
    PDF = "pdf"
    DOCX = "docx"
    IMAGE = "image"


class TaskKind(str, Enum):
    """导入任务类型。"""

    PAPER = "paper"
    ANSWER_SHEET = "answer_sheet"


class TaskStatus(str, Enum):
    """任务 / 识别状态。"""

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


class Severity(str, Enum):
    """失分点严重程度。"""

    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


def enum_values(enum_cls: type[Enum]) -> list[Any]:
    """取出枚举的全部 value（生成 JSON Schema 时使用）。"""
    return [member.value for member in enum_cls]
