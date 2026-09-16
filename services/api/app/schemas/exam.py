"""考试、试卷、答题卡 Schema。"""

from __future__ import annotations

from datetime import date

from pydantic import Field, field_validator

from app.models.enums import ExamType, SourceType, TaskStatus
from app.schemas.common import DateOnly, Paged, Schema, UtcDatetime


class ExamCreate(Schema):
    """创建考试请求。"""

    name: str = Field(..., min_length=1, max_length=64, description="考试名称，如「高三上第一次月考」")
    exam_date: date = Field(..., description="考试日期，YYYY-MM-DD")
    grade: str = Field(..., min_length=1, max_length=32, description="年级，如「高三」")
    exam_type: ExamType | None = Field(None, description="考试类型：月考 / 期中 / 期末 / 模拟")
    total_full_score: int = Field(750, ge=0, le=2000, description="总分满分")
    note: str | None = Field(None, max_length=500, description="备注")


class ExamOut(Schema):
    """考试摘要。"""

    id: str = Field(..., description="考试 ID")
    name: str = Field(..., description="考试名称")
    exam_date: DateOnly = Field(..., description="考试日期")
    grade: str = Field(..., description="年级")
    exam_type: str | None = Field(None, description="考试类型")
    total_full_score: int = Field(..., description="总分满分")
    subject_count: int = Field(0, description="已录入的学科数（按答题卡去重）")
    analysis_status: str = Field("none", description="分析状态：none / pending / running / succeeded / failed")
    created_at: UtcDatetime = Field(..., description="创建时间")


class ExamPage(Paged[ExamOut]):
    """考试分页。"""


class PaperOut(Schema):
    """题目 / 试卷。"""

    id: str = Field(..., description="试卷 ID")
    exam_id: str = Field(..., description="所属考试 ID")
    subject_code: str = Field(..., description="学科 code")
    title: str = Field("", description="标题")
    source_type: SourceType = Field(..., description="来源类型")
    object_keys: list[str] = Field(default_factory=list, description="存储对象 key 列表")
    ocr_status: TaskStatus = Field(TaskStatus.PENDING, description="识别状态")
    ocr_text: str = Field("", description="识别出的题目文本")
    question_count: int = Field(0, description="题目数量（识别后填充）")
    created_at: UtcDatetime = Field(..., description="创建时间")


class AnswerSheetOut(Schema):
    """答题卡。"""

    id: str = Field(..., description="答题卡 ID")
    exam_id: str = Field(..., description="所属考试 ID")
    paper_id: str | None = Field(None, description="关联试卷 ID")
    subject_code: str = Field(..., description="学科 code")
    source_type: SourceType = Field(..., description="来源类型")
    is_graded: bool = Field(True, description="是否已由老师批改")
    object_keys: list[str] = Field(default_factory=list, description="存储对象 key 列表")
    ocr_status: TaskStatus = Field(TaskStatus.PENDING, description="识别状态")
    score: float | None = Field(None, description="实际得分")
    full_score: float | None = Field(None, description="满分")
    created_at: UtcDatetime = Field(..., description="创建时间")


class AnalysisBrief(Schema):
    """考试详情里的「最新分析摘要」。"""

    id: str = Field(..., description="分析 ID")
    status: TaskStatus = Field(..., description="分析状态")
    total_score: float | None = Field(None, description="总分")
    full_score: float | None = Field(None, description="总分满分")
    score_rate: float | None = Field(None, description="总分得分率 0~1")
    created_at: UtcDatetime = Field(..., description="创建时间")


class ExamDetail(ExamOut):
    """考试详情。"""

    note: str = Field("", description="备注")
    papers: list[PaperOut] = Field(default_factory=list, description="题目 / 试卷列表")
    answer_sheets: list[AnswerSheetOut] = Field(default_factory=list, description="答题卡列表")
    latest_analysis: AnalysisBrief | None = Field(None, description="最新一次分析摘要")


class PaperCreate(Schema):
    """登记一份题目 / 试卷。"""

    subject_code: str = Field(..., min_length=1, max_length=32, description="学科 code，如 math")
    title: str = Field("", max_length=128, description="标题")
    source_type: SourceType = Field(..., description="来源类型：photo / pdf / docx / image")
    object_keys: list[str] = Field(..., min_length=1, description="已上传文件的 object_key 列表")

    @field_validator("object_keys")
    @classmethod
    def _dedupe(cls, value: list[str]) -> list[str]:
        """去重并保持顺序。"""
        seen: list[str] = []
        for item in value:
            if item and item not in seen:
                seen.append(item)
        if not seen:
            raise ValueError("object_keys 不能为空")
        return seen


class AnswerSheetCreate(Schema):
    """登记一份答题卡。"""

    subject_code: str = Field(..., min_length=1, max_length=32, description="学科 code，如 math")
    paper_id: str | None = Field(None, description="关联的试卷 ID，可选")
    source_type: SourceType = Field(..., description="来源类型：photo / pdf / docx / image")
    is_graded: bool = Field(True, description="是否已由老师批改")
    object_keys: list[str] = Field(..., min_length=1, description="已上传文件的 object_key 列表")
    score: float | None = Field(None, ge=0, description="实际得分（已批改时填）")
    full_score: float | None = Field(None, ge=0, description="满分")

    @field_validator("object_keys")
    @classmethod
    def _dedupe(cls, value: list[str]) -> list[str]:
        """去重并保持顺序。"""
        seen: list[str] = []
        for item in value:
            if item and item not in seen:
                seen.append(item)
        if not seen:
            raise ValueError("object_keys 不能为空")
        return seen


class PaperCreateResult(Schema):
    """登记试卷结果。"""

    paper_id: str = Field(..., description="试卷 ID")
    task_id: str = Field(..., description="后台识别任务 ID，可轮询 /tasks/{task_id}")


class AnswerSheetCreateResult(Schema):
    """登记答题卡结果。"""

    answer_sheet_id: str = Field(..., description="答题卡 ID")
    task_id: str = Field(..., description="后台识别任务 ID，可轮询 /tasks/{task_id}")
