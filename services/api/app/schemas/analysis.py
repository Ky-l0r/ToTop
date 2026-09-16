"""分析结果 Schema。

`AnalysisResult` 同时承担两个角色：
1. AI Provider 的**结构化输出契约**（qwen 的 JSON、mock 的假数据都按它解析校验）；
2. 落库到 `Analysis.result` 的 JSON 结构。

`AnalysisDetail` 在它之上补上 ID / 状态 / 总分类信息，直接作为 `GET /analyses/{id}` 的响应。
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from pydantic import Field, field_validator

from app.models.enums import Severity, TaskStatus
from app.schemas.common import Paged, Schema, UtcDatetime, clamp_rate, _to_float

if TYPE_CHECKING:  # 仅用于类型标注，避免运行期循环导入
    from app.models.analysis import Analysis


class WeakPointEvidence(Schema):
    """失分证据（原题、学生作答、正确答案、错误类型）。"""

    question_no: str = Field("", description="题号，如 18(1)")
    question_text: str = Field("", description="题干（可截断）")
    student_answer: str = Field("", description="学生作答")
    correct_answer: str = Field("", description="正确答案 / 标准答案要点")
    error_type: str = Field("", description="错误类型，如 概念混淆 / 计算失误 / 审题不清")


class WeakPoint(Schema):
    """一个失分知识点。"""

    id: str = Field(..., description="失分点 ID（结果内唯一）")
    subject_code: str = Field(..., description="学科 code")
    subject_name: str = Field(..., description="学科名称")
    module: str = Field("", description="所属模块")
    chapter: str = Field("", description="所属章节")
    knowledge_point: str = Field("", description="具体知识点")
    severity: Severity = Field(Severity.MEDIUM, description="严重程度：high / medium / low")
    lost_score: float = Field(0, description="失分分值")
    evidence: WeakPointEvidence = Field(default_factory=WeakPointEvidence, description="失分证据")
    suggestion: str = Field("", description="针对性提升建议")

    @field_validator("severity", mode="before")
    @classmethod
    def _coerce_severity(cls, value: Any) -> Any:
        """模型可能返回中文或大小写不一致的严重度，这里做一次归一。"""
        if value is None:
            return Severity.MEDIUM
        text = str(value).strip().lower()
        mapping = {
            "high": Severity.HIGH,
            "medium": Severity.MEDIUM,
            "low": Severity.LOW,
            "高": Severity.HIGH,
            "中": Severity.MEDIUM,
            "低": Severity.LOW,
            "严重": Severity.HIGH,
            "一般": Severity.MEDIUM,
            "轻微": Severity.LOW,
        }
        return mapping.get(text, Severity.MEDIUM)

    @field_validator("lost_score", mode="before")
    @classmethod
    def _coerce_lost(cls, value: Any) -> float:
        """容错处理 `"8分"` 这类返回。"""
        return _to_float(value)

    @field_validator("id", mode="before")
    @classmethod
    def _coerce_id(cls, value: Any) -> str:
        """模型可能不给 id，补一个稳定值（由服务层再统一编号）。"""
        return str(value) if value else "wp"


class SubjectScore(Schema):
    """单科得分情况。"""

    subject_code: str = Field(..., description="学科 code")
    subject_name: str = Field(..., description="学科名称")
    score: float = Field(0, description="得分")
    full_score: float = Field(0, description="满分")
    rate: float = Field(0, description="得分率 0~1")
    weak_module_names: list[str] = Field(default_factory=list, description="薄弱模块名列表")

    @field_validator("score", "full_score", mode="before")
    @classmethod
    def _coerce_number(cls, value: Any) -> float:
        """兼容 `"95分"` 之类的返回。"""
        return _to_float(value)

    @field_validator("rate", mode="before")
    @classmethod
    def _coerce_rate(cls, value: Any) -> float:
        """得分率容错；缺失时返回 0，由服务层按分数补算。"""
        return _to_float(value)


class ImprovementStep(Schema):
    """提升计划中的一个步骤。"""

    title: str = Field(..., description="步骤标题")
    detail: str = Field("", description="具体做法")
    estimated_hours: float = Field(0, description="预计投入小时数")
    resources: list[str] = Field(default_factory=list, description="推荐资料")

    @field_validator("estimated_hours", mode="before")
    @classmethod
    def _coerce_hours(cls, value: Any) -> float:
        """兼容 `"2.5 小时"` 这类返回。"""
        return _to_float(value)


class WeeklyPlanItem(Schema):
    """周计划条目。"""

    week: int = Field(1, description="第几周")
    focus: str = Field("", description="本周重点")
    target: str = Field("", description="本周目标")


class ImprovementPlan(Schema):
    """整体提升方案。"""

    summary: str = Field("", description="整体诊断结论")
    priority_order: list[str] = Field(default_factory=list, description="优先攻克顺序（知识点名）")
    steps: list[ImprovementStep] = Field(default_factory=list, description="提升步骤")
    weekly_plan: list[WeeklyPlanItem] = Field(default_factory=list, description="周计划")


class AnalysisResult(Schema):
    """AI 分析的结构化结果（Provider 的输出契约）。"""

    subjects: list[SubjectScore] = Field(default_factory=list, description="各科得分")
    weak_points: list[WeakPoint] = Field(default_factory=list, description="失分知识点")
    improvement_plan: ImprovementPlan = Field(default_factory=ImprovementPlan, description="提升方案")
    model_name: str = Field("", description="产出该结果的模型名")
    prompt_tokens: int = Field(0, description="提示词 token 数")
    completion_tokens: int = Field(0, description="补全 token 数")


class AnalysisDetail(AnalysisResult):
    """`GET /analyses/{id}` 的完整响应。"""

    id: str = Field(..., description="分析 ID")
    exam_id: str = Field(..., description="考试 ID")
    answer_sheet_id: str | None = Field(None, description="答题卡 ID")
    status: TaskStatus = Field(..., description="分析状态")
    total_score: float = Field(0, description="总分")
    full_score: float = Field(0, description="总分满分")
    score_rate: float = Field(0, description="总分得分率 0~1")
    credits_cost: int = Field(0, description="本次消耗点数")
    created_at: UtcDatetime = Field(..., description="创建时间")
    finished_at: UtcDatetime | None = Field(None, description="完成时间")
    error: str | None = Field(None, description="失败原因")

    @classmethod
    def from_model(cls, analysis: "Analysis") -> "AnalysisDetail":
        """把 ORM 对象（含 JSON 结果）转换成对外结构；未完成时返回空结果而不是报错。"""
        raw: dict[str, Any] = analysis.result or {}
        if raw:
            result = AnalysisResult.model_validate(raw)
        else:
            result = AnalysisResult(model_name=analysis.model_name or "")
        return cls(
            id=analysis.id,
            exam_id=analysis.exam_id,
            answer_sheet_id=analysis.answer_sheet_id,
            status=analysis.status,
            total_score=analysis.total_score or 0,
            full_score=analysis.full_score or 0,
            score_rate=analysis.score_rate or 0,
            credits_cost=analysis.credits_cost or 0,
            created_at=analysis.created_at,
            finished_at=analysis.finished_at,
            error=analysis.error,
            subjects=result.subjects,
            weak_points=result.weak_points,
            improvement_plan=result.improvement_plan,
            model_name=analysis.model_name or result.model_name,
            prompt_tokens=analysis.prompt_tokens or result.prompt_tokens,
            completion_tokens=analysis.completion_tokens or result.completion_tokens,
        )


class AnalysisPage(Paged[AnalysisDetail]):
    """分析列表分页。"""


class AnalysisCreate(Schema):
    """发起分析请求。"""

    exam_id: str = Field(..., min_length=1, description="考试 ID")
    answer_sheet_id: str | None = Field(None, description="答题卡 ID；不传则自动取该考试最新的一张")
    force: bool = Field(False, description="已存在成功结果时是否强制重新分析（会再次扣费）")


class AnalysisCreated(Schema):
    """发起分析结果。"""

    analysis_id: str = Field(..., description="分析 ID")
    status: TaskStatus = Field(..., description="初始状态，一般为 pending")
    credits_cost: int = Field(..., description="本次扣费点数")
    credits_balance: int = Field(..., description="扣费后的点数余额")


def recompute_subject_rate(subject: SubjectScore) -> SubjectScore:
    """当模型没给 rate（或给了 0）时，用 score/full_score 补算。"""
    if subject.full_score and (not subject.rate or subject.rate <= 0):
        subject.rate = clamp_rate(subject.score / subject.full_score)
    else:
        subject.rate = clamp_rate(subject.rate)
    return subject
