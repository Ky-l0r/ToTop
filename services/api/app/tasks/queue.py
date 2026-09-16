"""后台任务调度（基于 `asyncio.create_task`）。

本项目的后台任务只有两类：**试卷 / 答题卡的 OCR 识别** 与 **AI 失分分析**，
都用协程实现，单机部署下进程内调度足够，因此不引入 Celery / RQ 等外部组件。

约定：

1. 任务状态落 `import_tasks` 表，前端轮询 `GET /tasks/{task_id}` 看进度；
2. 所有派发的协程都会登记进 `_running`，进程退出时统一取消，避免残留；
3. 协程内部的异常**必须由协程自己捕获并写入业务表**（见 `app.services.analysis`），
   本模块的 done 回调只是兜底日志，绝不让异常静默消失。
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Coroutine
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.utils import utcnow
from app.db.session import session_scope
from app.models.analysis import ImportTask
from app.models.enums import TaskKind, TaskStatus

logger = logging.getLogger("app.tasks.queue")

__all__ = [
    "active_count",
    "create_task_record",
    "mark_failed",
    "shutdown",
    "spawn",
    "update_task",
]

# 终态：写入这些状态时自动补 finished_at
_TERMINAL_STATUS = {TaskStatus.SUCCEEDED, TaskStatus.FAILED}

_running: set[asyncio.Task[None]] = set()


async def create_task_record(
    db: AsyncSession,
    *,
    user_id: str,
    kind: TaskKind,
    exam_id: str | None = None,
    message: str = "已受理，排队中",
    result_ref: str | None = None,
) -> ImportTask:
    """创建一条后台任务记录并 flush（拿到 ID 后再由端点提交）。

    先写库再派发协程，保证前端拿到的 `task_id` 一定能查到。
    """
    task = ImportTask(
        user_id=user_id,
        exam_id=exam_id,
        kind=kind,
        status=TaskStatus.PENDING,
        progress=0,
        message=message,
        result_ref=result_ref,
    )
    db.add(task)
    await db.flush()
    return task


def spawn(coro: Coroutine[Any, Any, None], *, name: str) -> asyncio.Task[None]:
    """把一个协程登记为后台任务（不阻塞当前请求）。"""
    task = asyncio.create_task(coro, name=name)
    _running.add(task)
    task.add_done_callback(_on_done)
    logger.debug("已派发后台任务 %s（当前 %s 个）", name, len(_running))
    return task


def _on_done(task: asyncio.Task[None]) -> None:
    """任务结束回调：清理登记并记录未捕获异常（兜底，正常路径不应触发）。"""
    _running.discard(task)
    if task.cancelled():
        logger.info("后台任务 %s 已被取消", task.get_name())
        return
    exc = task.exception()
    if exc is not None:
        # 任务的业务异常应由协程内部写入 Analysis.error / ImportTask.error；
        # 走到这里说明漏了处理，必须打出来而不是丢掉。
        logger.error("后台任务 %s 出现未捕获异常", task.get_name(), exc_info=exc)


async def update_task(
    task_id: str,
    *,
    status: TaskStatus | None = None,
    progress: int | None = None,
    message: str | None = None,
    error: str | None = None,
    result_ref: str | None = None,
) -> None:
    """更新任务进度（独立事务，后台协程使用）。"""
    async with session_scope() as db:
        task = await db.get(ImportTask, task_id)
        if task is None:
            logger.warning("更新任务失败：任务 %s 不存在", task_id)
            return
        if status is not None:
            task.status = status
            if status in _TERMINAL_STATUS:
                task.finished_at = utcnow()
        if progress is not None:
            task.progress = max(0, min(int(progress), 100))
        if message is not None:
            task.message = message[:255]
        if error is not None:
            task.error = error
        if result_ref is not None:
            task.result_ref = result_ref


async def mark_failed(task_id: str, error: str) -> None:
    """把任务标记为失败并记录原因。"""
    await update_task(
        task_id,
        status=TaskStatus.FAILED,
        progress=100,
        message="处理失败",
        error=error[:2000],
    )


def active_count() -> int:
    """当前仍在运行的后台任务数（供健康检查 / 测试观察）。"""
    return len(_running)


async def shutdown(timeout: float = 5.0) -> None:
    """取消全部后台任务并等待退出（应用关闭时调用）。"""
    pending = [task for task in _running if not task.done()]
    if not pending:
        return
    logger.info("正在取消 %s 个后台任务…", len(pending))
    for task in pending:
        task.cancel()
    done, still_pending = await asyncio.wait(pending, timeout=timeout)
    if still_pending:
        logger.warning("仍有 %s 个后台任务未在 %.1f 秒内退出", len(still_pending), timeout)
    for task in done:
        _running.discard(task)
