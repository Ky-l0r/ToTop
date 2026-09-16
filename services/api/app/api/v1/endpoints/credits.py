"""点数端点：余额、流水、激励视频广告进度与奖励。

**广告奖励规则**（与 `docs/02-API契约.md` 第 4 节一致）：

- 每上报一次有效观看（`event_id` 全局唯一）进度 +1；
- 进度达到 `settings.ad_watch_needed`（默认 10）时发放 `settings.ad_reward_credits`（默认 1）点数并把进度清零；
- 同一 `event_id` 重复上报**只计一次**：接口返回业务码 `0`（成功），
  但 `credited=false`、`duplicated=true`，进度与余额都不变（详见 README「关键约定」）。

「进度」的存储方式：`ad_events.credited=true` 的那条记录表示它触发了发奖，
进度 = 该用户最后一条 `credited=true` 记录之后的观看条数。这样无需额外的计数表，
且天然能承受回调乱序/重发。
"""

from __future__ import annotations

import logging

from fastapi import APIRouter
from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import CurrentUser, DbSession, PageParams
from app.core.config import settings
from app.core.response import EnvelopeRoute
from app.models.credits import AdEvent
from app.models.enums import AdPlatform, AdType, CreditReason
from app.schemas.credits import (
    AdProgressResult,
    AdRewardProgress,
    AdRewardRequest,
    AdRewardResult,
    BalanceResult,
    CreditLedgerItem,
    CreditLedgerPage,
)
from app.services.credits import add_credits, get_balance, list_ledger

logger = logging.getLogger("app.api.credits")

router = APIRouter(route_class=EnvelopeRoute, prefix="/credits", tags=["credits"])


@router.get("/balance", response_model=BalanceResult, summary="点数余额")
async def read_balance(user: CurrentUser) -> BalanceResult:
    """当前点数余额。"""
    return BalanceResult(credits=user.credits)


@router.get("/ledger", response_model=CreditLedgerPage, summary="点数流水")
async def read_ledger(page: PageParams, user: CurrentUser, db: DbSession) -> CreditLedgerPage:
    """分页查询点数明细（按时间倒序）。"""
    rows, total = await list_ledger(db, user_id=user.id, offset=page.offset, limit=page.limit)
    return CreditLedgerPage(
        items=[CreditLedgerItem.model_validate(row) for row in rows],
        total=total,
    )


@router.get("/ad-progress", response_model=AdProgressResult, summary="广告观看进度")
async def read_ad_progress(user: CurrentUser, db: DbSession) -> AdProgressResult:
    """当前累计周期内的观看次数、兑换门槛与点数余额。"""
    return AdProgressResult(
        watched=await _watched_count(db, user.id),
        needed=max(settings.ad_watch_needed, 1),
        per_reward=settings.ad_reward_credits,
        credits=user.credits,
    )


@router.post("/ad-reward", response_model=AdRewardResult, summary="上报广告观看")
async def report_ad_reward(payload: AdRewardRequest, user: CurrentUser, db: DbSession) -> AdRewardResult:
    """客户端看完一次激励视频后上报；满足次数即发放点数。"""
    # 注意：先取出标量值，避免后续 rollback 让 ORM 对象过期后再访问属性
    user_id = user.id
    needed = max(settings.ad_watch_needed, 1)
    event_id = payload.event_id.strip()

    existing = await db.scalar(select(AdEvent.id).where(AdEvent.event_id == event_id))
    if existing is not None:
        return await _duplicated_result(db, user_id=user_id, event_id=event_id, needed=needed)

    event = AdEvent(
        user_id=user_id,
        platform=_parse_platform(payload.platform),
        event_id=event_id,
        ad_type=AdType.REWARD_VIDEO,
        watched_seconds=max(int(payload.watched_seconds), 0),
        credited=False,
    )
    db.add(event)
    try:
        await db.flush()
    except IntegrityError:
        # 并发重复上报：唯一约束兜底，按幂等处理
        await db.rollback()
        return await _duplicated_result(db, user_id=user_id, event_id=event_id, needed=needed)

    watched = await _watched_count(db, user_id)
    credits = int(await get_balance(db, user_id) or 0)
    credited = False

    if watched >= needed:
        # 本次上报触发发奖：把它标记为「发奖点」，进度随之为 0
        event.credited = True
        change = await add_credits(
            db,
            user_id=user_id,
            delta=settings.ad_reward_credits,
            reason=CreditReason.AD_REWARD,
            idempotency_key=f"ad:{event_id}",
            ref_type="ad_event",
            ref_id=event.id,
            remark=f"累计观看 {needed} 次激励视频奖励",
        )
        credits = change.balance_after
        credited = True
        watched = 0
        logger.info("用户 %s 广告奖励发放 %s 点（event_id=%s）", user_id, settings.ad_reward_credits, event_id)

    await db.commit()
    return AdRewardResult(
        credited=credited,
        credits=credits,
        progress=AdRewardProgress(watched=watched, needed=needed),
        duplicated=False,
    )


async def _duplicated_result(db: AsyncSession, *, user_id: str, event_id: str, needed: int) -> AdRewardResult:
    """重复上报的统一响应（业务码 0，进度与余额不变）。"""
    watched = await _watched_count(db, user_id)
    credits = int(await get_balance(db, user_id) or 0)
    logger.info("广告事件 %s 重复上报，已按幂等忽略", event_id)
    return AdRewardResult(
        credited=False,
        credits=credits,
        progress=AdRewardProgress(watched=watched, needed=needed),
        duplicated=True,
    )


async def _watched_count(db: AsyncSession, user_id: str) -> int:
    """当前周期内已观看次数（最后一条发奖记录之后的观看条数）。"""
    last_credited = await db.scalar(
        select(AdEvent)
        .where(AdEvent.user_id == user_id, AdEvent.credited.is_(True))
        .order_by(AdEvent.created_at.desc(), AdEvent.id.desc())
        .limit(1)
    )

    conditions = [AdEvent.user_id == user_id]
    if last_credited is not None:
        # created_at 可能同秒，再用 id 兜底排序
        conditions.append(
            or_(
                AdEvent.created_at > last_credited.created_at,
                and_(AdEvent.created_at == last_credited.created_at, AdEvent.id > last_credited.id),
            )
        )

    total = await db.scalar(select(func.count()).select_from(AdEvent).where(*conditions))
    return int(total or 0)


def _parse_platform(value: str) -> AdPlatform:
    """把客户端传来的平台字符串转成枚举；未知值回退为 mock（不阻断上报）。"""
    try:
        return AdPlatform(str(value).strip().lower())
    except ValueError:
        logger.warning("未知广告平台 %r，按 mock 记录", value)
        return AdPlatform.MOCK
