"""Pydantic v2 通用基础：时间序列化注解、分页、通用响应结构。"""

from __future__ import annotations

from datetime import date, datetime
from typing import Annotated, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer, field_validator

from app.core.utils import to_date_str, to_iso_z

T = TypeVar("T")


def _to_float(value: object) -> float:
    """把 AI 可能返回的 `"8分"` / `"8.5"` / `None` 之类统一转成 float。"""
    if value is None or isinstance(value, bool):
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if not text:
        return 0.0
    cleaned = "".join(ch for ch in text if ch.isdigit() or ch in ".-")
    try:
        return float(cleaned)
    except ValueError:
        return 0.0


UtcDatetime = Annotated[
    datetime,
    PlainSerializer(to_iso_z, return_type=str, when_used="json"),
]
"""输出为带 `Z` 的 ISO 8601 字符串（如 `2026-09-01T08:30:00Z`）。"""

DateOnly = Annotated[
    date,
    PlainSerializer(to_date_str, return_type=str, when_used="json"),
]
"""输出为 `YYYY-MM-DD`。"""


class Schema(BaseModel):
    """所有业务 Schema 的基类。"""

    model_config = ConfigDict(from_attributes=True, populate_by_name=True, str_strip_whitespace=True)


class Paged(Schema, Generic[T]):
    """分页响应。"""

    items: list[T] = Field(default_factory=list, description="当前页数据")
    total: int = Field(0, description="总条数")


class OkResult(Schema):
    """通用「操作成功」响应。"""

    ok: bool = Field(True, description="是否成功")


class HealthResult(Schema):
    """健康检查结果。"""

    status: str = Field("ok", description="服务状态")
    version: str = Field(..., description="服务版本号")
    ai_provider: str = Field(..., description="当前生效的 AI Provider（qwen / mock）")
    db: str = Field(..., description="数据库类型（sqlite / postgresql）")
    db_ok: bool = Field(True, description="数据库连通性")
    env: str = Field("dev", description="运行环境")


def clamp_rate(rate: float) -> float:
    """把得分率裁剪到 0~1 并保留 4 位小数。"""
    return round(min(max(rate, 0.0), 1.0), 4)


__all__ = [
    "DateOnly",
    "HealthResult",
    "OkResult",
    "Paged",
    "Schema",
    "UtcDatetime",
    "clamp_rate",
    "_to_float",
]
