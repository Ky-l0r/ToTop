"""AI Provider 工厂：按配置返回 qwen 或 mock，并保持单例（复用 HTTP 连接池）。"""

from __future__ import annotations

import logging

from app.core.config import settings
from app.providers.ai.base import AIProvider, AnalysisInput
from app.providers.ai.mock import MockProvider
from app.providers.ai.qwen import QwenProvider

logger = logging.getLogger("app.providers.ai")

__all__ = ["AIProvider", "AnalysisInput", "MockProvider", "QwenProvider", "get_ai_provider", "reset_ai_provider"]

_provider: AIProvider | None = None


def _build_provider() -> AIProvider:
    """按配置构造 Provider；qwen 初始化失败时降级为 mock，保证服务可用。"""
    name = settings.effective_ai_provider
    if name == "qwen":
        try:
            provider = QwenProvider()
            logger.info("AI Provider 已启用：qwen（模型 %s）", provider.model_name)
            return provider
        except Exception as exc:  # noqa: BLE001 —— 配置问题不应该让整个服务起不来
            logger.error("初始化 QwenProvider 失败，降级为 Mock：%s", exc)
    else:
        logger.info("AI Provider 已启用：mock（未配置 DASHSCOPE_API_KEY）")
    return MockProvider()


def get_ai_provider() -> AIProvider:
    """获取全局 Provider 单例。"""
    global _provider
    if _provider is None:
        _provider = _build_provider()
    return _provider


def reset_ai_provider() -> None:
    """重置单例（测试 / 热切换配置时使用）。"""
    global _provider
    _provider = None
