"""点数账本服务：余额原子增减 + 幂等流水。

设计约定（与 `docs/01-技术方案.md` 5.3 节一致）：

- 余额以 `users.credits` 为准，`credit_ledgers` 只增不改，`balance_after` 是快照；
- **任何**变动都必须带 `idempotency_key`（`analysis:{id}` / `order:{no}` / `ad:{event_id}` /
  `register:{user_id}` / `refund:{id}`），重复调用只生效一次；
- 扣减用一条 `UPDATE ... WHERE credits >= :cost` 完成「校验 + 扣减」，检查影响行数：
  0 行即余额不足，抛 `CreditNotEnoughError`（40200），调用方因此不会产生任何副作用；
- 本模块**不提交事务**：调用方把扣费与业务写入（如建 `Analysis`）放在同一个事务里，
  要么一起成功，要么一起回滚。

幂等的实现分两层：先查账本命中则直接返回；并发下由 `idempotency_key` 的唯一约束兜底
（此时会抛 `IntegrityError`，由调用方的事务回滚处理）。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import CreditNotEnoughError, ErrorCode, NotFoundError, ParamError
from app.core.utils import new_id, utcnow
from app.models.credits import CreditLedger
from app.models.enums import CreditReason
from app.models.user import User

logger = logging.getLogger("app.services.credits")

__all__ = ["CreditChange", "add_credits", "get_balance", "list_ledger", "spend_credits"]


@dataclass(slots=True)
class CreditChange:
    """一次点数变动的结果。"""

    changed: bool
    """是否真的发生了变动（`False` 表示命中幂等，未重复记账）。"""
    delta: int
    """本次变动值，正数为增加、负数为消耗。"""
    balance_after: int
    """变动后的余额。"""
    ledger_id: str | None = None
    """账本流水 ID。"""
    reason: CreditReason | None = None
    """变动原因。"""


async def get_balance(db: AsyncSession, user_id: str) -> int | None:
    """读取用户当前点数余额；用户不存在时返回 `None`。"""
    return await db.scalar(select(User.credits).where(User.id == user_id))


async def add_credits(
    db: AsyncSession,
    *,
    user_id: str,
    delta: int,
    reason: CreditReason,
    idempotency_key: str | None = None,
    ref_type: str | None = None,
    ref_id: str | None = None,
    remark: str = "",
) -> CreditChange:
    """增加点数并写账本（不提交）。

    :param delta: 增加的点数，必须为正数
    :param idempotency_key: 幂等键；重复调用只生效一次
    """
    if delta <= 0:
        raise ParamError("增加点数必须为正数", detail={"delta": delta})

    existing = await _find_by_idempotency_key(db, idempotency_key)
    if existing is not None:
        balance = await get_balance(db, user_id)
        if balance is None:
            raise NotFoundError("用户不存在", detail={"user_id": user_id})
        logger.info("点数增加命中幂等键 %s，跳过重复记账", idempotency_key)
        return CreditChange(
            changed=False,
            delta=existing.delta,
            balance_after=balance,
            ledger_id=existing.id,
            reason=existing.reason,
        )

    result = await db.execute(
        update(User).where(User.id == user_id).values(credits=User.credits + delta, updated_at=utcnow())
    )
    if not result.rowcount:
        raise NotFoundError("用户不存在", detail={"user_id": user_id})

    balance = await get_balance(db, user_id)
    if balance is None:  # pragma: no cover —— UPDATE 成功即用户存在，仅作防御
        raise NotFoundError("用户不存在", detail={"user_id": user_id})

    ledger = _make_ledger(
        user_id=user_id,
        delta=delta,
        reason=reason,
        balance_after=balance,
        idempotency_key=idempotency_key,
        ref_type=ref_type,
        ref_id=ref_id,
        remark=remark,
    )
    db.add(ledger)
    logger.info("点数增加 %+d（用户 %s，余额 %s，原因 %s）", delta, user_id, balance, reason.value)
    return CreditChange(changed=True, delta=delta, balance_after=balance, ledger_id=ledger.id, reason=reason)


async def spend_credits(
    db: AsyncSession,
    *,
    user_id: str,
    cost: int,
    reason: CreditReason = CreditReason.ANALYSIS,
    idempotency_key: str | None = None,
    ref_type: str | None = None,
    ref_id: str | None = None,
    remark: str = "",
    not_enough_message: str | None = None,
) -> CreditChange:
    """原子扣减点数并写账本（不提交）。

    扣减语句为 `UPDATE users SET credits = credits - :cost WHERE id = :uid AND credits >= :cost`，
    影响行数为 0 时说明余额不足，直接抛 `CreditNotEnoughError`（40200），
    调用方不会执行任何后续写入。

    :param cost: 需要扣减的点数，必须为正数
    :param not_enough_message: 余额不足时的提示文案（默认由异常类提供）
    """
    if cost <= 0:
        raise ParamError("扣减点数必须为正数", detail={"cost": cost})

    existing = await _find_by_idempotency_key(db, idempotency_key)
    if existing is not None:
        balance = await get_balance(db, user_id)
        if balance is None:
            raise NotFoundError("用户不存在", detail={"user_id": user_id})
        logger.info("点数扣减命中幂等键 %s，跳过重复扣减", idempotency_key)
        return CreditChange(
            changed=False,
            delta=existing.delta,
            balance_after=balance,
            ledger_id=existing.id,
            reason=existing.reason,
        )

    result = await db.execute(
        update(User)
        .where(User.id == user_id, User.credits >= cost)
        .values(credits=User.credits - cost, updated_at=utcnow())
    )
    if not result.rowcount:
        balance = await get_balance(db, user_id)
        if balance is None:
            raise NotFoundError("用户不存在", detail={"user_id": user_id})
        raise CreditNotEnoughError(
            not_enough_message or f"点数不足（本次需要 {cost} 点，当前余额 {balance} 点）",
            code=ErrorCode.CREDIT_NOT_ENOUGH,
            detail={"required": cost, "balance": balance},
        )

    balance = await get_balance(db, user_id)
    if balance is None:  # pragma: no cover —— 防御性分支
        raise NotFoundError("用户不存在", detail={"user_id": user_id})

    ledger = _make_ledger(
        user_id=user_id,
        delta=-cost,
        reason=reason,
        balance_after=balance,
        idempotency_key=idempotency_key,
        ref_type=ref_type,
        ref_id=ref_id,
        remark=remark,
    )
    db.add(ledger)
    logger.info("点数扣减 %+d（用户 %s，余额 %s，原因 %s）", -cost, user_id, balance, reason.value)
    return CreditChange(changed=True, delta=-cost, balance_after=balance, ledger_id=ledger.id, reason=reason)


async def list_ledger(
    db: AsyncSession,
    *,
    user_id: str,
    offset: int = 0,
    limit: int = 20,
) -> tuple[list[CreditLedger], int]:
    """分页查询某用户的点数流水（按时间倒序），返回 `(流水列表, 总数)`。"""
    total = await db.scalar(
        select(func.count()).select_from(CreditLedger).where(CreditLedger.user_id == user_id)
    )
    rows = await db.scalars(
        select(CreditLedger)
        .where(CreditLedger.user_id == user_id)
        .order_by(CreditLedger.created_at.desc(), CreditLedger.id.desc())
        .offset(offset)
        .limit(limit)
    )
    return list(rows), int(total or 0)


def _make_ledger(
    *,
    user_id: str,
    delta: int,
    reason: CreditReason,
    balance_after: int,
    idempotency_key: str | None,
    ref_type: str | None,
    ref_id: str | None,
    remark: str,
) -> CreditLedger:
    """构造一条账本流水。"""
    return CreditLedger(
        id=new_id(),
        user_id=user_id,
        delta=delta,
        reason=reason,
        balance_after=balance_after,
        ref_type=ref_type,
        ref_id=ref_id,
        remark=remark,
        idempotency_key=idempotency_key,
    )


async def _find_by_idempotency_key(db: AsyncSession, key: str | None) -> CreditLedger | None:
    """按幂等键查已有流水。"""
    if not key:
        return None
    return await db.scalar(select(CreditLedger).where(CreditLedger.idempotency_key == key))
