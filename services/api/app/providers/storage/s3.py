"""S3 / 阿里云 OSS 兼容存储（可选后端）。

实现依赖 `boto3`（可选安装：`.venv\\Scripts\\python.exe -m pip install boto3`）。
未安装时 `S3Storage(...)` 会抛出 `ImportError`，由 `app.providers.storage.get_storage_provider()`
捕获并把后端降级为本地存储，同时在日志中给出明确提示。

注意：本实现按 S3 兼容协议编写，**尚未在真实 OSS/S3 环境验证过**（见 README「待实现 / 风险」）。
生产启用前请先用真实 Bucket 跑一遍 `scripts/smoke_test.py`。
"""

from __future__ import annotations

import logging
import uuid
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.errors import ErrorCode, UpstreamError
from app.core.utils import utcnow
from app.providers.storage.base import StorageProvider, StoredObject

logger = logging.getLogger("app.providers.storage.s3")

_ALLOWED_KINDS = {"paper", "answer_sheet", "avatar", "misc"}


class S3Storage(StorageProvider):
    """基于 S3 兼容接口的对象存储。"""

    name = "s3"

    def __init__(
        self,
        *,
        endpoint_url: str | None = None,
        bucket: str | None = None,
        access_key: str | None = None,
        secret_key: str | None = None,
        region: str | None = None,
        presign_expire_seconds: int | None = None,
        public_base_url: str | None = None,
    ) -> None:
        try:
            import boto3  # type: ignore[import-not-found]  # 可选依赖
        except ImportError as exc:  # pragma: no cover —— 取决于运行环境是否装了 boto3
            raise ImportError("使用 S3/OSS 存储需要先安装可选依赖：pip install boto3") from exc

        self._bucket = bucket or settings.s3_bucket
        if not self._bucket:
            raise ValueError("未配置 S3_BUCKET")
        self._public_base_url = (public_base_url or settings.s3_endpoint_url or "").rstrip("/")
        self._presign_expire = presign_expire_seconds or settings.s3_presign_expire_seconds
        self._client = boto3.client(
            "s3",
            endpoint_url=endpoint_url or settings.s3_endpoint_url or None,
            aws_access_key_id=access_key or settings.s3_access_key or None,
            aws_secret_access_key=secret_key or settings.s3_secret_key or None,
            region_name=region or settings.s3_region or None,
        )

    # ------------------------------------------------------------ 内部
    @staticmethod
    def _build_key(filename: str, kind: str) -> str:
        """生成对象 key：`<kind>/<yyyymm>/<uuid><ext>`。"""
        safe_kind = (kind or "misc").strip().lower()
        if safe_kind not in _ALLOWED_KINDS:
            safe_kind = "misc"
        suffix = Path(filename or "").suffix.lower() or ".bin"
        return f"{safe_kind}/{utcnow().strftime('%Y%m')}/{uuid.uuid4().hex}{suffix}"

    # ------------------------------------------------------------ 接口实现
    async def save(self, data: bytes, *, filename: str, content_type: str = "", kind: str = "misc") -> StoredObject:
        """上传文件到 Bucket。"""
        object_key = self._build_key(filename, kind)
        try:
            self._client.put_object(
                Bucket=self._bucket,
                Key=object_key,
                Body=data,
                ContentType=content_type or "application/octet-stream",
            )
        except Exception as exc:  # noqa: BLE001 —— boto3 异常类型繁杂，统一转成上游错误
            logger.exception("上传对象存储失败：%s", object_key)
            raise UpstreamError("文件保存失败", code=ErrorCode.STORAGE_ERROR) from exc
        return StoredObject(
            object_key=object_key,
            url=self.url_for(object_key),
            size=len(data),
            content_type=content_type or "application/octet-stream",
        )

    def url_for(self, object_key: str) -> str:
        """生成可访问 URL。

        私有 Bucket 用带签名的临时 URL；配置了 CDN / 自定义域名时优先使用它。
        """
        try:
            return self._client.generate_presigned_url(
                "get_object",
                Params={"Bucket": self._bucket, "Key": object_key},
                ExpiresIn=self._presign_expire,
            )
        except Exception:  # noqa: BLE001 —— 签名失败时退回普通 URL
            logger.warning("生成预签名 URL 失败，回退为直接 URL", exc_info=True)
            if self._public_base_url:
                return f"{self._public_base_url}/{self._bucket}/{object_key}"
            return f"s3://{self._bucket}/{object_key}"

    async def read_bytes(self, object_key: str) -> bytes:
        """下载对象。"""
        try:
            response = self._client.get_object(Bucket=self._bucket, Key=object_key)
            return response["Body"].read()
        except Exception as exc:  # noqa: BLE001
            logger.warning("读取对象失败：%s", object_key, exc_info=True)
            raise FileNotFoundError(object_key) from exc

    async def exists(self, object_key: str) -> bool:
        """判断对象是否存在。"""
        try:
            self._client.head_object(Bucket=self._bucket, Key=object_key)
            return True
        except Exception:  # noqa: BLE001 —— head 失败即视为不存在
            return False

    async def delete(self, object_key: str) -> bool:
        """删除对象。"""
        try:
            self._client.delete_object(Bucket=self._bucket, Key=object_key)
            return True
        except Exception:  # noqa: BLE001
            logger.warning("删除对象失败：%s", object_key, exc_info=True)
            return False

    async def presign_put(
        self, *, filename: str, content_type: str = "", kind: str = "misc"
    ) -> dict[str, Any] | None:
        """生成客户端直传（PUT）所需的预签名信息。"""
        object_key = self._build_key(filename, kind)
        try:
            url = self._client.generate_presigned_url(
                "put_object",
                Params={"Bucket": self._bucket, "Key": object_key, "ContentType": content_type or "application/octet-stream"},
                ExpiresIn=self._presign_expire,
            )
        except Exception:  # noqa: BLE001
            logger.warning("生成直传预签名失败", exc_info=True)
            return None
        return {
            "object_key": object_key,
            "upload_url": url,
            "method": "PUT",
            "headers": {"Content-Type": content_type or "application/octet-stream"},
            "expires_in": self._presign_expire,
        }
