"""短信验证码服务。

存储策略：**验证码优先写 Redis，数据库表 `sms_codes` 作为审计与降级通道**。

- Redis 正常时：`sms:code:{scene}:{phone}` 存验证码（`SETEX`，TTL 见 `SMS_CODE_TTL_SECONDS`），
  校验走 Redis，快且天然过期；
- Redis 不可用时（本地开发常见）：校验回落到 `sms_codes` 表，功能不受影响；
- 发送频率与每日次数**始终以数据库记录为准**，避免「Redis 重启后限制失效」被刷短信；
- 开发环境（`ENV=dev` 且 `SMS_DEV_RETURN_CODE=true`）把验证码写日志并通过 `dev_code` 回传，
  生产必须关闭（`SMS_DEV_RETURN_CODE=false`）。

:raises ParamError: 发送过于频繁（40012）、验证码错误（40010）、验证码过期（40011）
"""

from __future__ import annotations

import logging
from datetime import timedelta

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import ErrorCode, ParamError
from app.core.rate_limit import get_redis
from app.core.utils import gen_sms_code, mask_phone, utcnow
from app.models.enums import SmsScene
from app.models.user import SmsCode
from app.schemas.auth import SmsSendResult

logger = logging.getLogger("app.services.sms")

__all__ = ["CODE_KEY_PREFIX", "send_code", "verify_code", "verify_code_for_scenes"]

CODE_KEY_PREFIX = "sms:code"
"""Redis 验证码 key 前缀：`sms:code:{scene}:{phone}`。"""


def _code_key(phone: str, scene: SmsScene) -> str:
    """验证码在 Redis 中的 key。"""
    return f"{CODE_KEY_PREFIX}:{scene.value}:{phone}"


async def send_code(db: AsyncSession, *, phone: str, scene: SmsScene) -> SmsSendResult:
    """发送验证码（不提交事务，由端点提交）。

    :returns: `SmsSendResult`，开发模式下 `dev_code` 会带上验证码便于自测
    """
    await _guard_send_interval(db, phone)
    await _guard_daily_limit(db, phone)

    code = gen_sms_code()
    expires_at = utcnow() + timedelta(seconds=settings.sms_code_ttl_seconds)

    db.add(
        SmsCode(
            phone=phone,
            code=code,
            scene=scene,
            expires_at=expires_at,
        )
    )
    await db.flush()

    await _cache_code(phone, scene, code)

    # 开发环境把验证码打进日志（生产环境 sms_provider=aliyun 时才真正下发短信）
    if settings.sms_provider == "dev" or settings.is_dev:
        logger.info(
            "【短信·模拟发送】%s 场景=%s 验证码=%s（%s 秒内有效）",
            mask_phone(phone),
            scene.value,
            code,
            settings.sms_code_ttl_seconds,
        )
    else:  # pragma: no cover —— 需要真实短信通道，当前未接入
        logger.warning("短信通道 %s 尚未实现真实发送，验证码仅写入数据库", settings.sms_provider)

    dev_code = code if (settings.sms_dev_return_code and settings.is_dev) else None
    return SmsSendResult(sent=True, dev_code=dev_code, expires_in=settings.sms_code_ttl_seconds)


async def verify_code(db: AsyncSession, *, phone: str, scene: SmsScene, code: str) -> None:
    """校验验证码，成功后立即作废（一次性使用）。

    :raises ParamError: 验证码错误（40010）或已过期（40011）
    """
    value = (code or "").strip()
    if not value:
        raise ParamError("请输入验证码", code=ErrorCode.SMS_CODE_INVALID)

    cached = await _load_cached_code(phone, scene)
    if cached is not None:
        if cached != value:
            raise ParamError("验证码不正确", code=ErrorCode.SMS_CODE_INVALID)
        await _consume_cached_code(phone, scene)
        await _mark_record_used(db, phone, scene, value)
        return

    # Redis 不可用或已过期：回落到数据库记录
    record = await db.scalar(
        select(SmsCode)
        .where(
            SmsCode.phone == phone,
            SmsCode.scene == scene,
            SmsCode.used.is_(False),
        )
        .order_by(SmsCode.created_at.desc(), SmsCode.id.desc())
        .limit(1)
    )
    if record is None:
        raise ParamError("验证码错误或已失效，请重新获取", code=ErrorCode.SMS_CODE_INVALID)
    if record.expires_at <= utcnow():
        raise ParamError("验证码已过期，请重新获取", code=ErrorCode.SMS_CODE_EXPIRED)
    if record.code != value:
        raise ParamError("验证码不正确", code=ErrorCode.SMS_CODE_INVALID)

    record.used = True
    await db.flush()


async def verify_code_for_scenes(
    db: AsyncSession,
    *,
    phone: str,
    scenes: tuple[SmsScene, ...],
    code: str,
) -> None:
    """按场景顺序校验验证码（用于「注册 / 登录」共用一个验证码的产品流程）。

    前端在验证码登录失败（40014 未注册）后会自动调用注册接口，
    此时手上的验证码是 `login` 场景发出的，因此这两个场景需要互相兼容：
    优先使用「确实存在验证码记录」的那个场景去校验，避免给出误导性的错误提示。
    """
    if not scenes:  # pragma: no cover —— 调用方保证非空
        raise ParamError("验证码场景未指定", code=ErrorCode.SMS_CODE_INVALID)

    for scene in scenes:
        if await _has_code(db, phone=phone, scene=scene):
            await verify_code(db, phone=phone, scene=scene, code=code)
            return

    # 所有场景都没有可用验证码：按主场景报错（错误文案更符合用户预期）
    await verify_code(db, phone=phone, scene=scenes[0], code=code)


async def _has_code(db: AsyncSession, *, phone: str, scene: SmsScene) -> bool:
    """判断某场景下是否还有未使用的验证码记录（Redis 或数据库任一命中即算）。"""
    if await _load_cached_code(phone, scene) is not None:
        return True
    record_id = await db.scalar(
        select(SmsCode.id)
        .where(
            SmsCode.phone == phone,
            SmsCode.scene == scene,
            SmsCode.used.is_(False),
        )
        .limit(1)
    )
    return record_id is not None


# ---------------------------------------------------------------------- 限频


async def _guard_send_interval(db: AsyncSession, phone: str) -> None:
    """同一手机号两次发送的最小间隔。"""
    interval = settings.sms_send_interval_seconds
    if interval <= 0:
        return
    last_sent = await db.scalar(
        select(SmsCode.created_at)
        .where(SmsCode.phone == phone)
        .order_by(SmsCode.created_at.desc())
        .limit(1)
    )
    if last_sent is None:
        return
    elapsed = (utcnow() - last_sent).total_seconds()
    if elapsed < interval:
        wait = int(interval - elapsed) + 1
        raise ParamError(
            f"发送过于频繁，请 {wait} 秒后再试",
            code=ErrorCode.SMS_SEND_TOO_FREQUENT,
            detail={"retry_after": wait},
        )


async def _guard_daily_limit(db: AsyncSession, phone: str) -> None:
    """同一手机号每天最多发送次数。"""
    limit = settings.sms_daily_limit_per_phone
    if limit <= 0:
        return
    day_start = utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
    sent_today = await db.scalar(
        select(func.count())
        .select_from(SmsCode)
        .where(SmsCode.phone == phone, SmsCode.created_at >= day_start)
    )
    if int(sent_today or 0) >= limit:
        raise ParamError(
            "今日验证码发送次数已达上限，请明日再试",
            code=ErrorCode.SMS_SEND_TOO_FREQUENT,
            detail={"daily_limit": limit},
        )


# ------------------------------------------------------------------ Redis 读写


async def _cache_code(phone: str, scene: SmsScene, code: str) -> None:
    """把验证码写入 Redis（Redis 不可用时静默跳过）。"""
    client = await get_redis()
    if client is None:
        return
    try:
        await client.setex(_code_key(phone, scene), settings.sms_code_ttl_seconds, code)
    except Exception:  # noqa: BLE001 —— Redis 抖动不影响主流程
        logger.warning("写入 Redis 验证码失败，本次校验将回落到数据库", exc_info=True)


async def _load_cached_code(phone: str, scene: SmsScene) -> str | None:
    """读取 Redis 中的验证码；Redis 不可用时返回 `None`。"""
    client = await get_redis()
    if client is None:
        return None
    try:
        value = await client.get(_code_key(phone, scene))
    except Exception:  # noqa: BLE001
        logger.warning("读取 Redis 验证码失败，回落到数据库", exc_info=True)
        return None
    return str(value) if value is not None else None


async def _consume_cached_code(phone: str, scene: SmsScene) -> None:
    """删除 Redis 中的验证码（一次性使用）。"""
    client = await get_redis()
    if client is None:
        return
    try:
        await client.delete(_code_key(phone, scene))
    except Exception:  # noqa: BLE001
        logger.warning("删除 Redis 验证码失败", exc_info=True)


async def _mark_record_used(db: AsyncSession, phone: str, scene: SmsScene, code: str) -> None:
    """把数据库中对应的验证码记录标记为已使用（Redis 命中时调用）。"""
    record = await db.scalar(
        select(SmsCode)
        .where(
            SmsCode.phone == phone,
            SmsCode.scene == scene,
            SmsCode.code == code,
            SmsCode.used.is_(False),
        )
        .order_by(SmsCode.created_at.desc())
        .limit(1)
    )
    if record is not None:
        record.used = True
        await db.flush()
