"""对象存储抽象。

本地开发默认使用 `LocalStorage`（写到 `services/api/storage/`，通过 `/static/` 访问）；
生产可切换为 `S3Storage`（S3/OSS 兼容）。切换方式见 `.env.example` 的 `STORAGE_BACKEND`。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

from pydantic import Field

from app.schemas.common import Schema


class StoredObject(Schema):
    """一次写入的结果。"""

    object_key: str = Field(..., description="对象 key")
    url: str = Field(..., description="可访问 URL")
    size: int = Field(..., description="字节数")
    content_type: str = Field("", description="MIME 类型")


class StorageProvider(ABC):
    """存储后端抽象。"""

    name: str = "base"

    @abstractmethod
    async def save(self, data: bytes, *, filename: str, content_type: str = "", kind: str = "misc") -> StoredObject:
        """写入一个文件并返回其 key / URL。"""

    @abstractmethod
    def url_for(self, object_key: str) -> str:
        """由 object_key 生成可访问 URL。"""

    @abstractmethod
    async def read_bytes(self, object_key: str) -> bytes:
        """读取文件内容。

        :raises FileNotFoundError: 对象不存在
        """

    @abstractmethod
    async def exists(self, object_key: str) -> bool:
        """判断对象是否存在。"""

    async def delete(self, object_key: str) -> bool:
        """删除对象（默认实现返回 False，表示该后端不支持）。"""
        return False

    async def presign_put(
        self, *, filename: str, content_type: str = "", kind: str = "misc"
    ) -> dict[str, Any] | None:
        """生成客户端直传所需的预签名信息；不支持直传的后端返回 None。"""
        return None

    async def aclose(self) -> None:
        """释放资源。"""
        return None
