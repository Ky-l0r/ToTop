"""分析结果与导入任务。"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import JSON, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.core.utils import new_id, utcnow
from app.db.base import Base
from app.models.enums import TaskKind, TaskStatus, enum_column


class Analysis(Base):
    """一次 AI 失分分析。

    `result` 存完整的结构化结果（见 `app.schemas.analysis.AnalysisResult`），
    前端直接渲染，不再做二次拼装。
    """

    __tablename__ = "analyses"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    exam_id: Mapped[str] = mapped_column(String(32), ForeignKey("exams.id", ondelete="CASCADE"), index=True, nullable=False)
    answer_sheet_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("answer_sheets.id", ondelete="SET NULL"), index=True, nullable=True
    )
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    status: Mapped[TaskStatus] = mapped_column(
        enum_column(TaskStatus, 16), default=TaskStatus.PENDING, index=True, nullable=False
    )
    credits_cost: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    total_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    full_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    score_rate: Mapped[float | None] = mapped_column(Float, nullable=True)
    result: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    model_name: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    prompt_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    completion_tokens: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class ImportTask(Base):
    """导入 / 后台任务状态表。

    由 `app.tasks.queue` 的 asyncio 后台协程驱动，前端轮询 `GET /tasks/{id}` 看进度。
    """

    __tablename__ = "import_tasks"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    exam_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("exams.id", ondelete="CASCADE"), index=True, nullable=True
    )
    kind: Mapped[TaskKind] = mapped_column(enum_column(TaskKind, 16), nullable=False)
    status: Mapped[TaskStatus] = mapped_column(
        enum_column(TaskStatus, 16), default=TaskStatus.PENDING, index=True, nullable=False
    )
    progress: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    message: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    result_ref: Mapped[str | None] = mapped_column(String(64), nullable=True)
    """任务产物 ID（如 paper_id / answer_sheet_id / analysis_id）。"""
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
