"""存储 Provider 工厂：按配置返回本地或 S3/OSS 实现。"""

from __future__ import annotations

import logging

from app.core.config import settings
from app.providers.storage.base import StorageProvider, StoredObject
from app.providers.storage.local import LocalStorage

logger = logging.getLogger("app.providers.storage")

__all__ = ["LocalStorage", "StorageProvider", "StoredObject", "get_storage_provider", "reset_storage_provider"]

_provider: StorageProvider | None = None


def _build_provider() -> StorageProvider:
    """按配置构造存储后端；S3 初始化失败时降级到本地存储。"""
    if settings.storage_backend == "s3":
        try:
            from app.providers.storage.s3 import S3Storage

            provider = S3Storage()
            logger.info("存储后端已启用：s3（bucket=%s）", settings.s3_bucket)
            return provider
        except Exception as exc:  # noqa: BLE001 —— 缺少 boto3 或配置不全时降级
            logger.error("初始化 S3 存储失败，降级为本地存储：%s", exc)
    return LocalStorage()


def get_storage_provider() -> StorageProvider:
    """获取全局存储单例。"""
    global _provider
    if _provider is None:
        _provider = _build_provider()
    return _provider


def reset_storage_provider() -> None:
    """重置单例（测试时使用）。"""
    global _provider
    _provider = None
