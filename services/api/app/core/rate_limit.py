"""限流：优先使用 Redis 计数，Redis 不可用时自动降级为进程内内存计数。

设计要点：
- Redis 使用固定窗口（`INCR` + `EXPIRE`），key 形如 `ratelimit:{scope}:{key}`；
- 连接失败时把失败时刻记下来，`REDIS_RETRY_INTERVAL` 秒内不再重试，避免每个请求都等超时；
- 降级到内存时使用滑动窗口（每个 key 一个时间戳列表），单进程内有效，
  多进程部署请务必保证 Redis 可用（见 README「待实现 / 风险」）。
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import Request

from app.core.config import settings
from app.core.errors import RateLimitedError

logger = logging.getLogger("app.rate_limit")

# Redis 重连冷却时间（秒）
REDIS_RETRY_INTERVAL = 30.0
# 内存模式下的 key 数量上限，超过时清理过期 key
_MEMORY_KEY_LIMIT = 10_000

_redis_client: Any | None = None
_redis_failed_until: float = 0.0
_redis_lock = asyncio.Lock()
_memory_buckets: dict[str, deque[float]] = defaultdict(deque)
_memory_lock = asyncio.Lock()
_memory_window_hint: dict[str, int] = {}


async def get_redis() -> Any | None:
    """获取可用的 Redis 客户端；不可用时返回 None（调用方需降级）。"""
    global _redis_client, _redis_failed_until

    if not settings.rate_limit_enabled:
        return None
    if _redis_client is not None:
        return _redis_client
    if time.monotonic() < _redis_failed_until:
        return None

    async with _redis_lock:
        if _redis_client is not None:
            return _redis_client
        if time.monotonic() < _redis_failed_until:
            return None
        try:
            from redis.asyncio import Redis  # 延迟导入：未安装 redis 时也能跑

            client = Redis.from_url(
                settings.redis_url,
                socket_connect_timeout=0.3,
                socket_timeout=0.3,
                decode_responses=True,
            )
            await client.ping()
        except Exception as exc:  # noqa: BLE001 —— 任何异常都意味着降级
            _redis_failed_until = time.monotonic() + REDIS_RETRY_INTERVAL
            logger.info("Redis 不可用（%s），限流降级为进程内内存计数", type(exc).__name__)
            return None

        _redis_client = client
        logger.info("Redis 限流已启用：%s", settings.redis_url)
        return _redis_client


async def reset_redis_client() -> None:
    """关闭并重置 Redis 客户端（应用退出时调用）。"""
    global _redis_client, _redis_failed_until
    client, _redis_client = _redis_client, None
    _redis_failed_until = 0.0
    if client is not None:
        try:
            await client.aclose()
        except Exception:  # noqa: BLE001 —— 关闭失败不影响退出
            logger.debug("关闭 Redis 连接失败", exc_info=True)


async def hit(scope: str, key: str, limit: int, window_seconds: int) -> tuple[bool, int]:
    """记一次访问，返回 `(是否放行, 剩余额度)`。"""
    if limit <= 0:
        return True, 0

    client = await get_redis()
    if client is not None:
        redis_key = f"ratelimit:{scope}:{key}"
        try:
            current = await client.incr(redis_key)
            if current == 1:
                await client.expire(redis_key, window_seconds)
            if current > limit:
                ttl = await client.ttl(redis_key)
                return False, max(int(ttl), 0)
            return True, limit - int(current)
        except Exception:  # noqa: BLE001 —— Redis 抖动时降级，不影响业务
            logger.warning("Redis 计数失败，本次降级为内存计数", exc_info=True)

    return await _memory_hit(scope, key, limit, window_seconds)


async def _memory_hit(scope: str, key: str, limit: int, window_seconds: int) -> tuple[bool, int]:
    """进程内滑动窗口计数。"""
    now = time.monotonic()
    bucket_key = f"{scope}:{key}"
    async with _memory_lock:
        if len(_memory_buckets) > _MEMORY_KEY_LIMIT:
            _cleanup_memory(now)
        bucket = _memory_buckets[bucket_key]
        _memory_window_hint[bucket_key] = window_seconds
        cutoff = now - window_seconds
        while bucket and bucket[0] <= cutoff:
            bucket.popleft()
        if len(bucket) >= limit:
            return False, max(int(window_seconds - (now - bucket[0])), 1)
        bucket.append(now)
        return True, limit - len(bucket)


def _cleanup_memory(now: float) -> None:
    """清理内存中已过期的计数桶。"""
    for bucket_key in list(_memory_buckets.keys()):
        bucket = _memory_buckets[bucket_key]
        window = _memory_window_hint.get(bucket_key, 60)
        while bucket and bucket[0] <= now - window:
            bucket.popleft()
        if not bucket:
            _memory_buckets.pop(bucket_key, None)
            _memory_window_hint.pop(bucket_key, None)


async def check(scope: str, key: str, limit: int, window_seconds: int, message: str | None = None) -> None:
    """限流校验；超限直接抛 429xx 业务异常。"""
    allowed, retry_after = await hit(scope, key, limit, window_seconds)
    if not allowed:
        raise RateLimitedError(
            message or f"操作过于频繁，请 {retry_after} 秒后再试",
            detail={"retry_after": retry_after},
        )


def client_ip(request: Request) -> str:
    """取客户端 IP（优先取反向代理写入的 X-Forwarded-For 第一段）。"""
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("x-real-ip")
    if real_ip:
        return real_ip.strip()
    return request.client.host if request.client else "unknown"


class RateLimit:
    """FastAPI 依赖：按 IP 限流。用法 `dependencies=[Depends(RateLimit("scope", 10, 60))]`。"""

    def __init__(self, scope: str, limit: int, window_seconds: int = 60, message: str | None = None) -> None:
        self.scope = scope
        self.limit = limit
        self.window_seconds = window_seconds
        self.message = message

    async def __call__(self, request: Request) -> None:
        await check(self.scope, client_ip(request), self.limit, self.window_seconds, self.message)


# 全局兜底限流：单 IP 每分钟请求数（在 main.py 中以中间件方式挂载）
GLOBAL_SCOPE = "global"
