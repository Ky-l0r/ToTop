"""AI Provider 抽象层。

约定一次分析（`analyze_exam`）的输入与输出契约：

- 输入 `AnalysisInput`：本次考试的基础信息 + 参与分析的各科答题卡（含题目文本、答题卡图片 data URL）；
- 输出 `AnalysisResult`：结构化结果（各科得分、失分点、提升方案），由服务层直接落库。

具体实现见 `qwen.py`（阿里云百炼 DashScope）与 `mock.py`（无 Key 时的假数据）。
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from pydantic import Field

from app.schemas.analysis import AnalysisResult
from app.schemas.common import Schema


class ExamInput(Schema):
    """分析输入中的考试信息。"""

    exam_id: str = Field(..., description="考试 ID")
    name: str = Field(..., description="考试名称")
    exam_date: str = Field(..., description="考试日期 YYYY-MM-DD")
    grade: str = Field("", description="年级")
    exam_type: str | None = Field(None, description="考试类型")
    total_full_score: float = Field(750, description="总分满分")


class SubjectInput(Schema):
    """分析输入中的单科信息。"""

    subject_code: str = Field(..., description="学科 code")
    subject_name: str = Field(..., description="学科名称")
    answer_sheet_id: str | None = Field(None, description="答题卡 ID")
    is_graded: bool = Field(True, description="是否老师已批改（已批改则以其分数为准）")
    score: float | None = Field(None, description="老师给出的实际得分")
    full_score: float | None = Field(None, description="该科满分")
    paper_text: str = Field("", description="题目文本（来自 Paper.ocr_text）")
    image_urls: list[str] = Field(default_factory=list, description="答题卡图片（data URL 或可访问 URL）")
    weak_module_hint: list[str] = Field(default_factory=list, description="该学科可选的知识模块提示")


class AnalysisInput(Schema):
    """一次分析的完整输入。"""

    exam: ExamInput = Field(..., description="考试信息")
    subjects: list[SubjectInput] = Field(default_factory=list, description="参与分析的学科")
    knowledge_modules: dict[str, list[str]] = Field(
        default_factory=dict, description="学科 code -> 知识模块名列表（用于对齐术语）"
    )
    extra_note: str = Field("", description="用户补充说明")


class AIProvider(ABC):
    """AI 能力抽象。

    实现类必须提供 `analyze_exam`；`extract_text` 用于试卷/答题卡的 OCR 文本提取，
    没有视觉能力的实现可以返回空串（但不要直接抛异常，服务层会兜底）。
    """

    #: Provider 标识（mock / qwen）
    name: str = "base"
    #: 默认模型名，会写入分析结果
    model_name: str = ""
    #: 是否具备真实视觉能力（用于日志与 README 说明）
    supports_vision: bool = False

    @abstractmethod
    async def analyze_exam(self, payload: AnalysisInput) -> AnalysisResult:
        """分析一次考试，返回结构化结果。

        :raises app.core.errors.UpstreamError: 上游模型调用失败（重试后仍失败）
        """

    @abstractmethod
    async def extract_text(self, image_urls: list[str], *, subject_name: str = "", hint: str = "") -> str:
        """从图片中提取文本（试卷题目 / 答题卡内容）。无能力时返回空串。"""

    async def aclose(self) -> None:
        """释放底层资源（HTTP 连接池等）。默认无需释放。"""
        return None
