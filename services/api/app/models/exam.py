"""考试、学科字典、知识点模块、题目（试卷）、答题卡。"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any

from sqlalchemy import JSON, Boolean, Date, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.utils import new_id, utcnow
from app.db.base import Base
from app.models.enums import ExamType, SourceType, TaskStatus, enum_column
from app.models.mixins import TimestampMixin


class Subject(Base):
    """学科字典表（语文/数学/英语/物理/化学/生物/政治/历史/地理）。"""

    __tablename__ = "subjects"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    code: Mapped[str] = mapped_column(String(32), unique=True, index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(32), nullable=False)
    sort: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class KnowledgeModule(Base):
    """知识点模块（学科 -> 模块 -> 章节 三级树，用 `parent_id` 自关联）。"""

    __tablename__ = "knowledge_modules"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    subject_code: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    parent_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("knowledge_modules.id", ondelete="CASCADE"), index=True, nullable=True
    )
    level: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    """1=模块，2=章节。"""
    sort: Mapped[int] = mapped_column(Integer, default=0, nullable=False)


class Exam(Base, TimestampMixin):
    """一次考试（一个考试下可以挂多份题目与多张答题卡）。"""

    __tablename__ = "exams"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    exam_date: Mapped[date] = mapped_column(Date, index=True, nullable=False)
    grade: Mapped[str] = mapped_column(String(32), default="", nullable=False)
    exam_type: Mapped[ExamType | None] = mapped_column(enum_column(ExamType, 16), nullable=True)
    total_full_score: Mapped[int] = mapped_column(Integer, default=750, nullable=False)
    note: Mapped[str] = mapped_column(Text, default="", nullable=False)

    papers: Mapped[list["Paper"]] = relationship(
        back_populates="exam", cascade="all, delete-orphan", passive_deletes=True, lazy="selectin"
    )
    answer_sheets: Mapped[list["AnswerSheet"]] = relationship(
        back_populates="exam", cascade="all, delete-orphan", passive_deletes=True, lazy="selectin"
    )


class Paper(Base, TimestampMixin):
    """题目 / 试卷：一次导入的原始题目文件及其 OCR 文本。"""

    __tablename__ = "papers"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    exam_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("exams.id", ondelete="CASCADE"), index=True, nullable=False
    )
    subject_code: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    title: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    source_type: Mapped[SourceType] = mapped_column(enum_column(SourceType, 16), nullable=False)
    object_keys: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    ocr_status: Mapped[TaskStatus] = mapped_column(
        enum_column(TaskStatus, 16), default=TaskStatus.PENDING, nullable=False
    )
    ocr_text: Mapped[str] = mapped_column(Text, default="", nullable=False)
    question_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    exam: Mapped[Exam] = relationship(back_populates="papers")


class AnswerSheet(Base, TimestampMixin):
    """答题卡：学生的作答图片，是 AI 失分分析的输入。"""

    __tablename__ = "answer_sheets"

    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=new_id)
    exam_id: Mapped[str] = mapped_column(
        String(32), ForeignKey("exams.id", ondelete="CASCADE"), index=True, nullable=False
    )
    paper_id: Mapped[str | None] = mapped_column(
        String(32), ForeignKey("papers.id", ondelete="SET NULL"), nullable=True
    )
    subject_code: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    source_type: Mapped[SourceType] = mapped_column(enum_column(SourceType, 16), nullable=False)
    is_graded: Mapped[bool] = mapped_column(Boolean, default=True, server_default="1", nullable=False)
    """是否已由老师批改：已批改时 AI 只做归因与提分建议，不重新判分。"""
    object_keys: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    ocr_status: Mapped[TaskStatus] = mapped_column(
        enum_column(TaskStatus, 16), default=TaskStatus.PENDING, nullable=False
    )
    ocr_result: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    score: Mapped[float | None] = mapped_column(Float, nullable=True)
    full_score: Mapped[float | None] = mapped_column(Float, nullable=True)

    exam: Mapped[Exam] = relationship(back_populates="answer_sheets")
