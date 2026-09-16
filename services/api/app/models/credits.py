"""点数账本、广告事件、充值套餐、订单。"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.utils import new_id, utcnow
from app.db.base import Base
from app.models.enums import AdPlatform, AdType, CreditReason, OrderStatus, PayChannel, enum_column


class CreditLedger(Base):
    """点数账本流水（只增不改，余额以 `User.credits` 为准，`balance_after` 为快照）。

    `idempotency_key` 唯一约束保证同一业务动作只入账一次（如同一订单、同一次分析、
    同一广告事件），并发重复请求会触发 IntegrityError 并被服务层识别为幂等命中。
    """

    __tablename__ = "credit_ledgers"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    delta: Mapped[int] = mapped_column(Integer, nullable=False)
    reason: Mapped[CreditReason] = mapped_column(enum_column(CreditReason), index=True, nullable=False)
    balance_after: Mapped[int] = mapped_column(Integer, nullable=False)
    ref_type: Mapped[str | None] = mapped_column(String(32), nullable=True)
    """关联对象类型：analysis / order / ad_event / register 等。"""
    ref_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    remark: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    idempotency_key: Mapped[str | None] = mapped_column(String(128), unique=True, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)


class AdEvent(Base):
    """广告观看事件。

    用于累计「看满 N 次激励视频换 1 点数」：`credited=True` 的那条事件表示它在本次
    兑换中触发了发奖，进度 = 该用户最后一条 `credited=True` 事件之后的观看条数。
    `event_id` 由客户端生成并唯一，保证同一次观看重复上报只计一次。
    """

    __tablename__ = "ad_events"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    platform: Mapped[AdPlatform] = mapped_column(enum_column(AdPlatform), nullable=False)
    event_id: Mapped[str] = mapped_column(String(128), unique=True, index=True, nullable=False)
    ad_type: Mapped[AdType] = mapped_column(enum_column(AdType), default=AdType.REWARD_VIDEO, nullable=False)
    watched_seconds: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    credited: Mapped[bool] = mapped_column(Boolean, default=False, server_default="0", nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)


class Plan(Base):
    """充值套餐（字典表，启动时按内置数据幂等补齐）。"""

    __tablename__ = "plans"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    credits: Mapped[int] = mapped_column(Integer, nullable=False)
    price_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    currency: Mapped[str] = mapped_column(String(8), default="CNY", nullable=False)
    channel: Mapped[PayChannel] = mapped_column(enum_column(PayChannel), nullable=False)
    apple_product_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True, server_default="1", nullable=False)
    sort: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)


class Order(Base):
    """充值订单。"""

    __tablename__ = "orders"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    order_no: Mapped[str] = mapped_column(String(40), unique=True, index=True, nullable=False)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    plan_id: Mapped[str | None] = mapped_column(String(32), ForeignKey("plans.id", ondelete="SET NULL"), nullable=True)
    credits: Mapped[int] = mapped_column(Integer, nullable=False)
    amount_cents: Mapped[int] = mapped_column(Integer, nullable=False)
    currency: Mapped[str] = mapped_column(String(8), default="CNY", nullable=False)
    channel: Mapped[PayChannel] = mapped_column(enum_column(PayChannel), nullable=False)
    status: Mapped[OrderStatus] = mapped_column(
        enum_column(OrderStatus), default=OrderStatus.PENDING, index=True, nullable=False
    )
    pay_params: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    """下单返回给客户端的支付参数（小程序拉起支付所需）。"""
    third_party_txn_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    paid_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow, nullable=False)
    remark: Mapped[str] = mapped_column(Text, default="", nullable=False)
