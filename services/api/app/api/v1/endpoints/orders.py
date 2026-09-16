"""充值套餐与订单端点。

定价约定（`docs/01-技术方案.md` R8）：

- Android / 鸿蒙：微信、支付宝 `¥1.00 = 1 点数`（1 点 = 100 分）；
- iOS：走 App Store 内购 `¥1.50 = 1 点数`（覆盖 30% 平台佣金）；
- 每个渠道都有 1 / 5 / 20 点三档，**5 点档单价最低且档位适中，标记为推荐**。

套餐数据存 `plans` 表：`GET /orders/plans` 首次访问时若表为空，自动灌入默认套餐（幂等）。

模拟支付 `POST /orders/{order_no}/pay` **只允许在开发 / 测试环境使用**（`ENV=dev|test`），
可由环境变量 `ENABLE_MOCK_PAY=true|false` 单独覆盖；生产环境一律走真实支付渠道回调
（`POST /orders/notify/{channel}`，验签逻辑见 README「待实现」）。
"""

from __future__ import annotations

import logging
import os
from collections.abc import Sequence
from typing import Any

from fastapi import APIRouter
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import CurrentUser, DbSession, PageParams
from app.core.config import settings
from app.core.errors import ErrorCode, ForbiddenError, NotFoundError, ParamError
from app.core.response import EnvelopeRoute
from app.core.utils import gen_order_no, utcnow
from app.models.credits import Order, Plan
from app.models.enums import CreditReason, OrderStatus, PayChannel
from app.schemas.order import CreateOrderRequest, OrderCreated, OrderOut, OrderPage, OrderPayResult, PlanOut
from app.services.credits import add_credits, get_balance

logger = logging.getLogger("app.api.orders")

router = APIRouter(route_class=EnvelopeRoute, prefix="/orders", tags=["orders"])

# 默认套餐字典（1 / 5 / 20 点三档；同一渠道内 5 点档单价最低，标为推荐）
_DEFAULT_PLANS: tuple[dict[str, Any], ...] = (
    {"channel": PayChannel.WECHAT, "name": "1 点数", "credits": 1, "price_cents": 100, "sort": 10},
    {"channel": PayChannel.WECHAT, "name": "5 点数", "credits": 5, "price_cents": 500, "sort": 20},
    {"channel": PayChannel.WECHAT, "name": "20 点数", "credits": 20, "price_cents": 2000, "sort": 30},
    {"channel": PayChannel.ALIPAY, "name": "1 点数", "credits": 1, "price_cents": 100, "sort": 10},
    {"channel": PayChannel.ALIPAY, "name": "5 点数", "credits": 5, "price_cents": 500, "sort": 20},
    {"channel": PayChannel.ALIPAY, "name": "20 点数", "credits": 20, "price_cents": 2000, "sort": 30},
    {
        "channel": PayChannel.APPLE_IAP,
        "name": "1 点数",
        "credits": 1,
        "price_cents": 150,
        "sort": 10,
        "apple_product_id": "totop.credits.1",
    },
    {
        "channel": PayChannel.APPLE_IAP,
        "name": "5 点数",
        "credits": 5,
        "price_cents": 750,
        "sort": 20,
        "apple_product_id": "totop.credits.5",
    },
    {
        "channel": PayChannel.APPLE_IAP,
        "name": "20 点数",
        "credits": 20,
        "price_cents": 3000,
        "sort": 30,
        "apple_product_id": "totop.credits.20",
    },
)


async def ensure_plans(db: AsyncSession) -> int:
    """套餐表为空时灌入默认套餐（不提交，由调用方决定事务边界）。"""
    count = int(await db.scalar(select(func.count()).select_from(Plan)) or 0)
    if count:
        return count

    for entry in _DEFAULT_PLANS:
        db.add(
            Plan(
                name=str(entry["name"]),
                credits=int(entry["credits"]),
                price_cents=int(entry["price_cents"]),
                currency=str(entry.get("currency", "CNY")),
                channel=entry["channel"],
                apple_product_id=entry.get("apple_product_id"),
                active=True,
                sort=int(entry["sort"]),
            )
        )
    await db.flush()
    logger.info("套餐表为空，已灌入 %s 个默认套餐", len(_DEFAULT_PLANS))
    return len(_DEFAULT_PLANS)


@router.get("/plans", response_model=list[PlanOut], summary="充值套餐列表")
async def list_plans(db: DbSession) -> list[PlanOut]:
    """返回全部上架套餐，并标记「最划算」的那一个（按渠道分别计算）。"""
    await ensure_plans(db)
    await db.commit()

    plans = list(
        await db.scalars(
            select(Plan).where(Plan.active.is_(True)).order_by(Plan.channel, Plan.sort, Plan.credits)
        )
    )
    recommended = _recommended_plan_ids(plans)
    return [
        PlanOut(
            id=plan.id,
            name=plan.name,
            credits=plan.credits,
            price_cents=plan.price_cents,
            currency=plan.currency,
            channel=plan.channel,
            recommended=plan.id in recommended,
        )
        for plan in plans
    ]


@router.post("", response_model=OrderCreated, summary="创建充值订单")
async def create_order(payload: CreateOrderRequest, user: CurrentUser, db: DbSession) -> OrderCreated:
    """创建订单并返回拉起支付所需参数（当前为占位参数，接入真实渠道后替换）。"""
    await ensure_plans(db)

    plan = await db.get(Plan, payload.plan_id)
    if plan is None or not plan.active:
        raise NotFoundError("套餐不存在或已下架", detail={"plan_id": payload.plan_id})
    if plan.channel != payload.channel:
        raise ParamError(
            "套餐与支付渠道不匹配",
            detail={"plan_channel": plan.channel.value, "request_channel": payload.channel.value},
        )

    order_no = gen_order_no()
    order = Order(
        order_no=order_no,
        user_id=user.id,
        plan_id=plan.id,
        credits=plan.credits,
        amount_cents=plan.price_cents,
        currency=plan.currency,
        channel=plan.channel,
        status=OrderStatus.PENDING,
        pay_params=_build_pay_params(plan, order_no),
    )
    db.add(order)
    await db.commit()
    logger.info("用户 %s 创建订单 %s（%s 点 / %s 分）", user.id, order_no, plan.credits, plan.price_cents)
    return OrderCreated(
        order_id=order.id,
        order_no=order.order_no,
        amount_cents=order.amount_cents,
        pay_params=order.pay_params or {},
    )


@router.post("/{order_no}/pay", response_model=OrderPayResult, summary="模拟支付（仅开发环境）")
async def pay_order(order_no: str, user: CurrentUser, db: DbSession) -> OrderPayResult:
    """开发环境模拟支付成功：到账点数 + 写账本（幂等键 `order:{order_no}`）。"""
    if not _mock_pay_enabled():
        raise ForbiddenError("模拟支付仅在开发环境可用，请使用真实支付渠道")

    order = await db.scalar(select(Order).where(Order.order_no == order_no, Order.user_id == user.id))
    if order is None:
        raise NotFoundError("订单不存在", detail={"order_no": order_no})

    if order.status == OrderStatus.PAID:
        # 重复支付：幂等返回当前状态，不再重复到账
        balance = int(await get_balance(db, user.id) or 0)
        logger.info("订单 %s 已支付，按幂等返回", order_no)
        return OrderPayResult(order_no=order.order_no, status=order.status, credits=balance)

    if order.status != OrderStatus.PENDING:
        raise ParamError(
            "订单状态不允许支付",
            code=ErrorCode.ORDER_STATUS_INVALID,
            detail={"status": order.status.value},
        )

    change = await add_credits(
        db,
        user_id=user.id,
        delta=order.credits,
        reason=CreditReason.PURCHASE,
        idempotency_key=f"order:{order.order_no}",
        ref_type="order",
        ref_id=order.order_no,
        remark=f"充值 {order.credits} 点数",
    )
    order.status = OrderStatus.PAID
    order.paid_at = utcnow()
    order.third_party_txn_id = f"mock-{order.order_no}"
    await db.commit()

    return OrderPayResult(order_no=order.order_no, status=order.status, credits=change.balance_after)


@router.post("/notify/{channel}", summary="支付平台异步回调（占位实现）")
async def payment_notify(channel: str) -> dict[str, bool]:
    """支付平台服务器回调入口。

    **当前为占位实现**：真实接入时必须完成「验签 → 幂等发货（`order:{order_no}`）→ 返回成功」
    三步，详见 README「待实现 / 风险」。这里返回成功只是为了联调时不被平台重试打爆。
    """
    logger.warning("收到 %s 渠道的支付回调，但验签与发货逻辑尚未实现（占位接口）", channel)
    return {"ok": True}


@router.get("", response_model=OrderPage, summary="我的订单列表")
async def list_orders(page: PageParams, user: CurrentUser, db: DbSession) -> OrderPage:
    """按创建时间倒序返回当前用户的订单。"""
    total = int(await db.scalar(select(func.count()).select_from(Order).where(Order.user_id == user.id)) or 0)
    rows = await db.scalars(
        select(Order)
        .where(Order.user_id == user.id)
        .order_by(Order.created_at.desc(), Order.id.desc())
        .offset(page.offset)
        .limit(page.limit)
    )
    return OrderPage(items=[OrderOut.model_validate(order) for order in rows], total=total)


# ---------------------------------------------------------------------- 内部工具


def _mock_pay_enabled() -> bool:
    """模拟支付开关：默认仅开发 / 测试环境开启，可用 `ENABLE_MOCK_PAY` 覆盖。"""
    raw = os.getenv("ENABLE_MOCK_PAY")
    if raw is None or not raw.strip():
        return settings.is_dev
    return raw.strip().lower() in ("1", "true", "yes", "on")


def _recommended_plan_ids(plans: Sequence[Plan]) -> set[str]:
    """挑出每个渠道「最划算」的套餐 ID。

    规则：单点价格（`price_cents / credits`）最低者优先；若有并列（本项目 1 / 5 / 20 点同价），
    取档位居中的那个——它既不像最小档那样频繁充值，也不像最大档那样一次性支出过多。
    """
    grouped: dict[PayChannel, list[Plan]] = {}
    for plan in plans:
        grouped.setdefault(plan.channel, []).append(plan)

    picked: set[str] = set()
    for peers in grouped.values():
        unit_prices = {plan.id: plan.price_cents / max(plan.credits, 1) for plan in peers}
        best = min(unit_prices.values())
        candidates = sorted(
            (plan for plan in peers if abs(unit_prices[plan.id] - best) < 1e-9),
            key=lambda plan: plan.credits,
        )
        picked.add(candidates[len(candidates) // 2].id)
    return picked


def _build_pay_params(plan: Plan, order_no: str) -> dict[str, Any]:
    """按渠道构造拉起支付所需的参数。

    **当前为占位参数**：真实接入时小程序端需 `wx.requestPayment` 的
    `timeStamp/nonceStr/package/signType/paySign`，App 端需支付宝 `orderStr`，
    iOS 需 App Store 的 `product_id`（由客户端直接走 StoreKit，服务端只做票据校验）。
    """
    base: dict[str, Any] = {
        "channel": plan.channel.value,
        "order_no": order_no,
        "mock": True,
        "amount_cents": plan.price_cents,
    }
    if plan.channel == PayChannel.WECHAT:
        base.update({"package": f"prepay_id=mock_{order_no}", "sign_type": "MD5", "pay_sign": "mock"})
    elif plan.channel == PayChannel.ALIPAY:
        base.update({"order_str": f"mock_alipay_order_str:{order_no}"})
    elif plan.channel == PayChannel.APPLE_IAP:
        base.update({"product_id": plan.apple_product_id or "", "quantity": plan.credits})
    return base
