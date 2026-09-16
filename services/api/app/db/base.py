"""SQLAlchemy 2.0 声明式基类。

- 统一 `type_annotation_map`，让 `dict` / `list` 注解自动映射为通用 `JSON` 类型
  （SQLite 与 PostgreSQL 都支持，不使用任何 PG 专有类型）；
- `import_all_models()` 在创建表 / 生成迁移前导入全部模型，保证元数据完整。
"""

from __future__ import annotations

import importlib
from typing import Any

from sqlalchemy import JSON, String
from sqlalchemy.orm import DeclarativeBase

# 需要注册到元数据的模型模块（新增模型文件时记得加进来）
MODEL_MODULES = (
    "app.models.user",
    "app.models.credits",
    "app.models.exam",
    "app.models.analysis",
)


class Base(DeclarativeBase):
    """全局声明式基类。"""

    type_annotation_map = {
        dict[str, Any]: JSON,
        list[str]: JSON,
        list[dict[str, Any]]: JSON,
        dict[str, str]: JSON,
        str: String(255),
    }


def import_all_models() -> None:
    """导入全部模型模块，确保 `Base.metadata` 中登记了所有表。"""
    for module in MODEL_MODULES:
        importlib.import_module(module)
