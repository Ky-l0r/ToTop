"""充值套餐与订单 Schema。"""

from __future__ import annotations

from typing import Any

from pydantic import Field

from app.models.enums import OrderStatus, PayChannel
from app.schemas.common import Paged, Schema, UtcDatetime


class PlanOut(Schema):
    """充值套餐。"""

    id: str = Field(..., description="套餐 ID")
    name: str = Field(..., description="套餐名，如「10 点数包」")
    credits: int = Field(..., description="包含点数")
    price_cents: int = Field(..., description="价格（分）")
    currency: str = Field("CNY", description="币种")
    channel: PayChannel = Field(..., description="支付渠道")
    recommended: bool = Field(False, description="是否为「最划算」推荐套餐（按单点价格最低计算）")


class CreateOrderRequest(Schema):
    """创建订单请求。"""

    plan_id: str = Field(..., min_length=1, description="套餐 ID")
    channel: PayChannel = Field(PayChannel.MOCK, description="支付渠道：wechat / alipay / apple_iap / mock")


class OrderCreated(Schema):
    """下单结果。"""

    order_id: str = Field(..., description="订单 ID")
    order_no: str = Field(..., description="订单号")
    amount_cents: int = Field(..., description="应付金额（分）")
    pay_params: dict[str, Any] = Field(default_factory=dict, description="拉起支付所需参数")


class OrderOut(Schema):
    """订单详情。"""

    id: str = Field(..., description="订单 ID")
    order_no: str = Field(..., description="订单号")
    credits: int = Field(..., description="购买点数")
    amount_cents: int = Field(..., description="金额（分）")
    currency: str = Field("CNY", description="币种")
    channel: PayChannel = Field(..., description="支付渠道")
    status: OrderStatus = Field(..., description="订单状态")
    created_at: UtcDatetime = Field(..., description="创建时间")
    paid_at: UtcDatetime | None = Field(None, description="支付时间")


class OrderPage(Paged[OrderOut]):
    """订单分页。"""


class OrderPayResult(Schema):
    """模拟支付结果。"""

    order_no: str = Field(..., description="订单号")
    status: OrderStatus = Field(..., description="支付后的订单状态")
    credits: int = Field(..., description="到账后的点数余额")
