"""上传与任务端点。

- `POST /uploads/direct`：客户端把拍照 / 相册 / 文件直接上传到服务端，
  由存储 Provider（本地磁盘或 S3/OSS）落盘，返回 `object_key` 供后续登记试卷 / 答题卡使用；
- `GET /tasks/{task_id}`：查询导入 / OCR 后台任务的进度。

上传校验（两道关，缺一不可）：

1. 扩展名必须在 `settings.allowed_upload_ext` 内，否则 40031；
2. 累计字节数不得超过 `settings.max_upload_bytes`，否则 40030（边读边计数，避免把超大文件读进内存再拒绝）。
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, File, Form, UploadFile

from app.api.deps import CurrentUser, DbSession
from app.core.config import settings
from app.core.errors import ErrorCode, NotFoundError, ParamError
from app.core.response import EnvelopeRoute
from app.models.analysis import ImportTask
from app.providers.storage import get_storage_provider
from app.schemas.misc import TaskOut, UploadResult

logger = logging.getLogger("app.api.uploads")

router = APIRouter(route_class=EnvelopeRoute, tags=["uploads"])

# 允许的 kind（同时作为存储目录名，见 LocalStorage）
_ALLOWED_KINDS = ("paper", "answer_sheet", "avatar")

# 读取上传文件的分块大小（字节）
_CHUNK_SIZE = 1024 * 1024


@router.post("/uploads/direct", response_model=UploadResult, summary="上传文件（服务端中转）")
async def upload_direct(
    user: CurrentUser,
    file: Annotated[UploadFile, File(description="要上传的文件（jpg/png/webp/pdf/docx 等）")],
    kind: Annotated[str, Form(description="用途：paper（题目）/ answer_sheet（答题卡）/ avatar（头像）")] = "paper",
) -> UploadResult:
    """校验并保存文件，返回 `object_key` 与可访问 URL。"""
    safe_kind = (kind or "paper").strip().lower()
    if safe_kind not in _ALLOWED_KINDS:
        raise ParamError(
            f"kind 只支持 {' / '.join(_ALLOWED_KINDS)}",
            detail={"kind": kind},
        )

    filename = (file.filename or "").strip()
    suffix = Path(filename).suffix.lower()
    if not suffix or suffix not in settings.allowed_upload_ext:
        raise ParamError(
            f"不支持的文件类型（仅支持 {'、'.join(settings.allowed_upload_ext)}）",
            code=ErrorCode.UPLOAD_TYPE_NOT_ALLOWED,
            detail={"filename": filename, "allowed": settings.allowed_upload_ext},
        )

    data = await _read_limited(file)
    if not data:
        raise ParamError("上传的文件内容为空", detail={"filename": filename})

    storage = get_storage_provider()
    stored = await storage.save(
        data,
        filename=filename,
        content_type=(file.content_type or "").strip(),
        kind=safe_kind,
    )
    logger.info("用户 %s 上传文件 %s（%s 字节，kind=%s）", user.id, stored.object_key, stored.size, safe_kind)
    return UploadResult(
        object_key=stored.object_key,
        url=stored.url,
        size=stored.size,
        content_type=stored.content_type,
        kind=safe_kind,
    )


@router.get("/tasks/{task_id}", response_model=TaskOut, summary="查询后台任务进度")
async def read_task(task_id: str, user: CurrentUser, db: DbSession) -> TaskOut:
    """查询试卷 / 答题卡的识别任务状态（前端轮询用）。"""
    task = await db.get(ImportTask, task_id)
    if task is None or task.user_id != user.id:
        raise NotFoundError("任务不存在", detail={"task_id": task_id})
    return TaskOut.model_validate(task)


async def _read_limited(file: UploadFile) -> bytes:
    """分块读取上传内容，超过大小上限立即拒绝（40030）。"""
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(_CHUNK_SIZE)
        if not chunk:
            break
        total += len(chunk)
        if total > settings.max_upload_bytes:
            raise ParamError(
                f"文件过大（上限 {settings.max_upload_mb} MB）",
                code=ErrorCode.UPLOAD_TOO_LARGE,
                detail={"max_bytes": settings.max_upload_bytes},
            )
        chunks.append(chunk)
    return b"".join(chunks)
