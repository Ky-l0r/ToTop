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

    summary: str = Field("", description="学情概述（「基础分析」阶段的正文），服务层会截断到 200 字以内")
    subjects: list[SubjectScore] = Field(default_factory=list, description="各科得分")
    weak_points: list[WeakPoint] = Field(default_factory=list, description="失分知识点")
    improvement_plan: ImprovementPlan = Field(default_factory=ImprovementPlan, description="提升方案")
    model_name: str = Field("", description="产出该结果的模型名")
    prompt_tokens: int = Field(0, description="提示词 token 数")
    completion_tokens: int = Field(0, description="补全 token 数")


# ======================================================================
# 分阶段解锁
#
# 设计：一次模型调用产出完整结果，三个阶段的内容**从同一份结果派生**——
# 这样图片只需送模型一次，成本最低；解锁只是「可见性」控制。
# ======================================================================

STAGE_SUMMARY_MAX_CHARS = 200
"""「基础分析」阶段的学情概述字数上限。"""

STAGE_MAX_WEAK_QUESTIONS = 10
"""「进阶分析」阶段最多展示的失分题数量。"""

STAGE_MAX_MODULES = 6
"""「归纳总结」阶段最多展示的知识模块数量。"""


class StageSummaryContent(Schema):
    """阶段 1「基础分析」：200 字以内的学情状况。"""

    summary: str = Field("", description="学情概述，不超过 200 字")
    score_rate: float = Field(0, description="总分得分率 0~1")
    total_score: float = Field(0, description="总分")
    full_score: float = Field(0, description="总分满分")
    level_text: str = Field("", description="水平档位：优秀 / 良好 / 及格 / 待提升")
    top_subjects: list[dict[str, Any]] = Field(default_factory=list, description="相对较好的学科")


class WeakQuestionItem(Schema):
    """阶段 2 里的一道失分题。"""

    question_no: str = Field("", description="题号，如 18(2)")
    subject_name: str = Field("", description="学科")
    module: str = Field("", description="所属模块")
    knowledge_point: str = Field("", description="具体知识点")
    lost_score: float = Field(0, description="失分分值")
    severity: Severity = Field(Severity.MEDIUM, description="严重程度")
    error_type: str = Field("", description="错误类型")
    brief: str = Field("", description="一句话说清错在哪")


class StageQuestionsContent(Schema):
    """阶段 2「进阶分析」：在哪些题目上失分严重。"""

    weak_questions: list[WeakQuestionItem] = Field(default_factory=list, description="按失分降序")
    total_lost: float = Field(0, description="这些题目的合计失分")


class StageModuleItem(Schema):
    """阶段 3 里需要重点补的知识模块。"""

    module: str = Field("", description="知识模块")
    subject_name: str = Field("", description="学科")
    lost_score_total: float = Field(0, description="该模块累计失分")
    priority: int = Field(1, description="优先级，1 最高")
    reason: str = Field("", description="为什么优先补它")


class StagePlanContent(Schema):
    """阶段 3「归纳总结」：重点补哪些模块 + 学习建议。"""

    modules: list[StageModuleItem] = Field(default_factory=list, description="按累计失分降序")
    suggestions: list[str] = Field(default_factory=list, description="学习建议")
    improvement_plan: ImprovementPlan = Field(default_factory=ImprovementPlan, description="原样透传的提升方案")


class AnalysisStage(Schema):
    """单个阶段的解锁状态与内容（未解锁时 content 为 null，内容不下发到客户端）。"""

    stage: int = Field(..., description="阶段序号：1 / 2 / 3")
    name: str = Field("", description="阶段名")
    ad_required: int = Field(1, description="解锁需要看完的激励视频次数")
    watched: int = Field(0, description="已累计的有效观看次数")
    unlocked: bool = Field(False, description="是否已解锁")
    unlock_method: str | None = Field(None, description="解锁方式：ad（看广告）/ credit（用点数）")
    content: dict[str, Any] | None = Field(None, description="阶段内容；未解锁时为 null")


class UnlockStageRequest(Schema):
    """上报一次激励视频观看。"""

    platform: str = Field("wechat", description="广告来源平台，用于统计与风控")
    event_id: str = Field(..., min_length=1, description="广告事件 ID，服务端据此幂等去重")
    watched_seconds: int = Field(30, description="本次广告实际观看秒数，低于阈值不计入进度")


class UnlockStageResult(Schema):
    """解锁上报的返回。"""

    stage: int = Field(..., description="阶段序号")
    watched: int = Field(0, description="累计有效观看次数")
    ad_required: int = Field(1, description="解锁所需次数")
    unlocked: bool = Field(False, description="是否已解锁")
    credited: bool = Field(True, description="本次上报是否被计入进度")
    duplicated: bool = Field(False, description="是否为重复上报（event_id 已处理过）")
    unlock_method: str | None = Field(None, description="解锁方式")
    content: dict[str, Any] | None = Field(None, description="已解锁时返回内容")
    credits_balance: int | None = Field(None, description="用点数解锁后的余额")


def truncate_summary(text: str, limit: int = STAGE_SUMMARY_MAX_CHARS) -> str:
    """把学情概述压到 limit 字以内，尽量收在句末标点上，避免半句话。"""
    cleaned = " ".join((text or "").split())
    if len(cleaned) <= limit:
        return cleaned
    head = cleaned[:limit]
    for punct in ("。", "！", "？", "；", ".", "!", "?", ";"):
        index = head.rfind(punct)
        if index >= limit // 2:
            return head[: index + 1]
    return head.rstrip("，,、 ") + "…"


def level_text(rate: float) -> str:
    """按得分率给出水平档位。"""
    if rate >= 0.85:
        return "优秀"
    if rate >= 0.7:
        return "良好"
    if rate >= 0.6:
        return "及格"
    return "待提升"


def _aggregate_modules(weak_points: list["WeakPoint"], limit: int = STAGE_MAX_MODULES) -> list[StageModuleItem]:
    """把失分点按「学科 + 模块」聚合，返回累计失分最高的若干个。"""
    buckets: dict[tuple[str, str], float] = {}
    for point in weak_points:
        module = point.module or point.chapter or "未归类模块"
        key = (point.subject_name or point.subject_code or "未知学科", module)
        buckets[key] = buckets.get(key, 0.0) + float(point.lost_score or 0)

    ordered = sorted(buckets.items(), key=lambda item: item[1], reverse=True)[:limit]
    modules: list[StageModuleItem] = []
    for index, ((subject_name, module), lost) in enumerate(ordered):
        if index < 2:
            priority = 1
        elif index < 4:
            priority = 2
        else:
            priority = 3
        error_types: list[str] = []
        for point in weak_points:
            if (point.module or point.chapter or "未归类模块") != module:
                continue
            kind = point.evidence.error_type if point.evidence else ""
            if kind and kind not in error_types:
                error_types.append(kind)
        reason = (
            f"累计失分 {round(lost, 1)} 分，错误类型集中在「{'、'.join(error_types[:2])}」。"
            if error_types
            else f"累计失分 {round(lost, 1)} 分，建议优先专项突破。"
        )
        modules.append(
            StageModuleItem(
                module=module,
                subject_name=subject_name,
                lost_score_total=round(lost, 1),
                priority=priority,
                reason=reason,
            )
        )
    return modules


def build_stage_contents(
    result: AnalysisResult,
    *,
    total_score: float,
    full_score: float,
    score_rate: float,
) -> dict[int, dict[str, Any]]:
    """从完整分析结果派生出三个阶段的内容。"""
    # ---- 阶段 1：学情概述
    raw_summary = result.summary or (result.improvement_plan.summary if result.improvement_plan else "")
    if not raw_summary:
        rate_text = f"{round(score_rate * 100, 1)}%"
        raw_summary = (
            f"本次考试总分得分率 {rate_text}（{round(total_score, 1)} / {round(full_score, 1)} 分），"
            f"整体处于{level_text(score_rate)}水平。共定位到 {len(result.weak_points)} 个失分点，"
            "建议先看进阶分析与归纳总结，按优先级逐个补齐。"
        )
    top_subjects = sorted(result.subjects, key=lambda item: item.rate, reverse=True)[:3]
    stage1 = StageSummaryContent(
        summary=truncate_summary(raw_summary),
        score_rate=round(score_rate, 4),
        total_score=round(total_score, 1),
        full_score=round(full_score, 1),
        level_text=level_text(score_rate),
        top_subjects=[
            {"code": item.subject_code, "name": item.subject_name, "rate": round(item.rate, 4)}
            for item in top_subjects
        ],
    )

    # ---- 阶段 2：失分严重的题目
    ordered_points = sorted(result.weak_points, key=lambda item: float(item.lost_score or 0), reverse=True)
    picked = ordered_points[:STAGE_MAX_WEAK_QUESTIONS]
    stage2 = StageQuestionsContent(
        weak_questions=[
            WeakQuestionItem(
                question_no=point.evidence.question_no if point.evidence else "",
                subject_name=point.subject_name,
                module=point.module or point.chapter,
                knowledge_point=point.knowledge_point,
                lost_score=round(float(point.lost_score or 0), 1),
                severity=point.severity,
                error_type=(point.evidence.error_type if point.evidence else "") or "待分析",
                brief=point.suggestion or (point.evidence.student_answer if point.evidence else ""),
            )
            for point in picked
        ],
        total_lost=round(sum(float(item.lost_score or 0) for item in picked), 1),
    )

    # ---- 阶段 3：知识模块与学习建议
    suggestions = [point.suggestion for point in ordered_points if point.suggestion][:6]
    if not suggestions and result.improvement_plan:
        suggestions = [step.detail or step.title for step in result.improvement_plan.steps if step.detail or step.title][:6]
    stage3 = StagePlanContent(
        modules=_aggregate_modules(result.weak_points),
        suggestions=suggestions,
        improvement_plan=result.improvement_plan or ImprovementPlan(),
    )

    return {
        1: stage1.model_dump(),
        2: stage2.model_dump(),
        3: stage3.model_dump(),
    }


def build_stages(
    analysis: "Analysis",
    *,
    ad_requirements: list[int],
    stage_names: list[str],
) -> list[AnalysisStage]:
    """按解锁状态组装三个阶段；未解锁的 content 一律为 null。"""
    raw: dict[str, Any] = analysis.result or {}
    result = (
        AnalysisResult.model_validate(raw)
        if raw
        else AnalysisResult(model_name=analysis.model_name or "")
    )
    contents = build_stage_contents(
        result,
        total_score=analysis.total_score or 0,
        full_score=analysis.full_score or 0,
        score_rate=analysis.score_rate or 0,
    )
    state: dict[str, Any] = analysis.stage_state or {}

    stages: list[AnalysisStage] = []
    for index in range(3):
        number = index + 1
        item: dict[str, Any] = state.get(str(number)) or {}
        unlocked = bool(item.get("unlocked"))
        stages.append(
            AnalysisStage(
                stage=number,
                name=stage_names[index] if index < len(stage_names) else f"第 {number} 阶段",
                ad_required=ad_requirements[index] if index < len(ad_requirements) else 1,
                watched=int(item.get("watched") or 0),
                unlocked=unlocked,
                unlock_method=item.get("unlock_method"),
                content=contents[number] if unlocked else None,
            )
        )
    return stages


def new_stage_state(ad_requirements: list[int]) -> dict[str, Any]:
    """初始化三个阶段的解锁状态。"""
    return {
        str(index + 1): {"watched": 0, "unlocked": False, "unlocked_at": None, "unlock_method": None}
        for index in range(len(ad_requirements))
    }


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
    stages: list[AnalysisStage] = Field(default_factory=list, description="三个阶段；未解锁的 content 为 null")

    @classmethod
    def from_model(
        cls, analysis: "Analysis", *, stages: list[AnalysisStage] | None = None
    ) -> "AnalysisDetail":
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
            stages=stages or [],
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
