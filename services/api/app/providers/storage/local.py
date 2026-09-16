"""本地磁盘存储：文件写入 `STORAGE_DIR`（默认 `services/api/storage/`），通过 `/static/` 对外访问。"""

from __future__ import annotations

import asyncio
import logging
import mimetypes
import uuid
from pathlib import Path

from app.core.config import settings
from app.core.errors import ErrorCode, UpstreamError
from app.core.utils import utcnow
from app.providers.storage.base import StorageProvider, StoredObject

logger = logging.getLogger("app.providers.storage.local")

# 允许的 kind 取值（作为一级目录，避免客户端传入任意路径片段）
_ALLOWED_KINDS = {"paper", "answer_sheet", "avatar", "misc"}


class LocalStorage(StorageProvider):
    """把文件存到本地磁盘。"""

    name = "local"

    def __init__(self, base_dir: Path | None = None, url_prefix: str | None = None) -> None:
        self.base_dir = Path(base_dir or settings.storage_dir).resolve()
        self.url_prefix = (url_prefix or settings.static_url_prefix).rstrip("/")
        self.base_dir.mkdir(parents=True, exist_ok=True)

    # ------------------------------------------------------------ 内部
    def _abs_path(self, object_key: str) -> Path:
        """把 object_key 映射为绝对路径，并阻止目录穿越。"""
        key = object_key.lstrip("/\\")
        target = (self.base_dir / key).resolve()
        if not target.is_relative_to(self.base_dir):
            raise UpstreamError("非法的对象 key", code=ErrorCode.STORAGE_ERROR, detail={"object_key": object_key})
        return target

    @staticmethod
    def _normalize_kind(kind: str) -> str:
        """归一化 kind，未知值一律落到 misc。"""
        value = (kind or "misc").strip().lower()
        return value if value in _ALLOWED_KINDS else "misc"

    # ------------------------------------------------------------ 接口实现
    async def save(self, data: bytes, *, filename: str, content_type: str = "", kind: str = "misc") -> StoredObject:
        """写入文件。"""
        safe_kind = self._normalize_kind(kind)
        suffix = Path(filename or "").suffix.lower()
        if not suffix:
            suffix = mimetypes.guess_extension(content_type or "") or ".bin"
        stamp = utcnow().strftime("%Y%m")
        object_key = f"{safe_kind}/{stamp}/{uuid.uuid4().hex}{suffix}"
        path = self._abs_path(object_key)

        try:
            await asyncio.to_thread(path.parent.mkdir, parents=True, exist_ok=True)
            await asyncio.to_thread(path.write_bytes, data)
        except OSError as exc:
            logger.exception("写入本地文件失败：%s", path)
            raise UpstreamError("文件保存失败", code=ErrorCode.STORAGE_ERROR, detail={"path": str(path)}) from exc

        if not content_type:
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return StoredObject(
            object_key=object_key,
            url=self.url_for(object_key),
            size=len(data),
            content_type=content_type,
        )

    def url_for(self, object_key: str) -> str:
        """生成可访问 URL（走本服务的 `/static/` 挂载点）。"""
        key = object_key.lstrip("/")
        return f"{settings.public_base_url.rstrip('/')}{self.url_prefix}/{key}"

    async def read_bytes(self, object_key: str) -> bytes:
        """读取文件内容。"""
        path = self._abs_path(object_key)
        if not path.is_file():
            raise FileNotFoundError(object_key)
        return await asyncio.to_thread(path.read_bytes)

    async def exists(self, object_key: str) -> bool:
        """判断文件是否存在。"""
        return await asyncio.to_thread(self._abs_path(object_key).is_file)

    async def delete(self, object_key: str) -> bool:
        """删除文件（不存在时返回 False）。"""
        path = self._abs_path(object_key)
        if not await asyncio.to_thread(path.is_file):
            return False
        await asyncio.to_thread(path.unlink)
        return True
