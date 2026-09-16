"""导入任务、上传、字典、趋势 Schema。"""

from __future__ import annotations

from pydantic import Field

from app.models.enums import TaskStatus
from app.schemas.common import DateOnly, Schema, UtcDatetime


# ------------------------------------------------------------------ 任务
class TaskOut(Schema):
    """后台任务状态。"""

    id: str = Field(..., description="任务 ID")
    status: TaskStatus = Field(..., description="状态：pending / running / succeeded / failed")
    progress: int = Field(0, description="进度 0~100")
    message: str = Field("", description="进度描述")
    error: str | None = Field(None, description="失败原因")
    result_ref: str | None = Field(None, description="产物 ID（paper_id / answer_sheet_id / analysis_id）")
    created_at: UtcDatetime | None = Field(None, description="创建时间")
    finished_at: UtcDatetime | None = Field(None, description="结束时间")


# ------------------------------------------------------------------ 上传
class UploadResult(Schema):
    """直传结果。"""

    object_key: str = Field(..., description="存储对象 key，后续登记试卷/答题卡时使用")
    url: str = Field(..., description="可访问的 URL")
    size: int = Field(..., description="文件大小（字节）")
    content_type: str = Field("", description="MIME 类型")
    kind: str = Field("", description="用途：paper / answer_sheet / avatar")


# ------------------------------------------------------------------ 字典
class ModuleMeta(Schema):
    """知识模块（含章节）。"""

    name: str = Field(..., description="模块名")
    chapters: list[str] = Field(default_factory=list, description="章节名列表")


class SubjectMeta(Schema):
    """学科及其知识模块树。"""

    code: str = Field(..., description="学科 code")
    name: str = Field(..., description="学科名称")
    modules: list[ModuleMeta] = Field(default_factory=list, description="模块列表")


# ------------------------------------------------------------------ 趋势
class TrendPoint(Schema):
    """趋势图上的一个考试节点。"""

    exam_id: str = Field(..., description="考试 ID")
    exam_name: str = Field(..., description="考试名称")
    exam_date: DateOnly = Field(..., description="考试日期")
    total_rate: float = Field(..., description="总分得分率 0~1")
    subjects: dict[str, float] = Field(default_factory=dict, description="学科 code -> 得分率 0~1")


class TrendSubject(Schema):
    """趋势图中出现过的学科。"""

    code: str = Field(..., description="学科 code")
    name: str = Field(..., description="学科名称")


class TrendData(Schema):
    """趋势数据。"""

    points: list[TrendPoint] = Field(default_factory=list, description="按考试日期正序排列的数据点")
    subjects: list[TrendSubject] = Field(default_factory=list, description="涉及到的学科")
