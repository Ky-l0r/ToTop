"""Mock AI Provider：没有 API Key 时提供结构合理的假数据，保证全链路可跑通。

数据来源是 `mock_sample.json`（可自由修改以定制演示效果）：
- 按 `subject_code` 匹配学科模板，匹配不到时回退到 `_default`；
- 分数会按 `exam_id + subject_code` 做确定性微抖动，让本地演示的趋势图有起伏但可复现；
- 失分点总数裁剪到 6~8 个，与真实模型的输出规模接近。
"""

from __future__ import annotations

import hashlib
import json
import logging
import random
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.providers.ai.base import AIProvider, AnalysisInput, SubjectInput
from app.schemas.analysis import (
    AnalysisResult,
    ImprovementPlan,
    ImprovementStep,
    SubjectScore,
    WeakPoint,
    WeakPointEvidence,
    WeeklyPlanItem,
)

logger = logging.getLogger("app.providers.ai.mock")

# 失分点数量的合理区间（与真实模型输出规模对齐）
MIN_WEAK_POINTS = 6
MAX_WEAK_POINTS = 8

_SEVERITY_ORDER = {"high": 0, "medium": 1, "low": 2}

# 匹配不到模板学科时使用的极简兜底（正常情况下 mock_sample.json 已覆盖主要学科）
_FALLBACK_SUBJECT: dict[str, Any] = {
    "subject_name": "综合",
    "full_score": 100,
    "score": 72,
    "weak_module_names": ["基础概念"],
    "weak_points": [
        {
            "module": "基础概念",
            "chapter": "核心概念理解",
            "knowledge_point": "概念辨析与规范表述",
            "severity": "high",
            "lost_score": 6,
            "evidence": {
                "question_no": "12",
                "question_text": "简答题：说明该概念的内涵与外延。",
                "student_answer": "仅写了内涵，未给出外延与适用条件。",
                "correct_answer": "需同时给出内涵、外延与适用条件，并举例说明。",
                "error_type": "概念回答不完整",
            },
            "suggestion": "把该学科核心概念按「定义—范围—条件—反例」四要素整理成表格，每周默写一次。",
        }
    ],
}

_FALLBACK_PLAN: dict[str, Any] = {
    "summary": "本次考试失分集中在综合应用类题目，属于方法熟练度不足，建议按模块逐个突破。",
    "priority_order": [],
    "steps": [],
    "weekly_plan": [],
}


class MockProvider(AIProvider):
    """返回假数据的 Provider（无需任何外部依赖与网络）。"""

    name = "mock"
    model_name = "mock-qwen-vl-max"
    supports_vision = False

    def __init__(self, sample_path: Path | None = None) -> None:
        self._sample_path = Path(sample_path) if sample_path else settings.mock_sample_path
        self._sample = self._load_sample()

    # ------------------------------------------------------------ 内部工具
    def _load_sample(self) -> dict[str, Any]:
        """读取样例模板；文件缺失或损坏时回退到内置极简模板。"""
        try:
            raw = self._sample_path.read_text(encoding="utf-8")
            data = json.loads(raw)
            if not isinstance(data, dict) or "subjects" not in data:
                raise ValueError("mock_sample.json 结构不正确：缺少 subjects 字段")
            return data
        except Exception as exc:  # noqa: BLE001 —— 任何问题都降级，不能让演示流程挂掉
            logger.warning("读取 Mock 样例失败（%s），改用内置兜底模板：%s", self._sample_path, exc)
            return {
                "model_name": self.model_name,
                "subjects": {"_default": _FALLBACK_SUBJECT},
                "improvement_plan": _FALLBACK_PLAN,
            }

    def _template_for(self, subject_code: str) -> dict[str, Any]:
        """取某学科的模板。"""
        subjects = self._sample.get("subjects", {})
        return subjects.get(subject_code) or subjects.get("_default") or _FALLBACK_SUBJECT

    @staticmethod
    def _jitter(base: float, full: float, rnd: random.Random) -> float:
        """在模板分数附近做 ±4 的确定性抖动，并保证落在 [0, full]。"""
        value = base + rnd.uniform(-4.0, 4.0)
        value = max(0.0, min(value, full))
        return round(value, 1)

    @staticmethod
    def _seed(exam_id: str, subject_code: str) -> int:
        """由考试与学科生成稳定随机种子（同一份数据每次结果一致）。"""
        digest = hashlib.sha256(f"{exam_id}:{subject_code}".encode("utf-8")).hexdigest()
        return int(digest[:8], 16)

    # ------------------------------------------------------------ 核心实现
    async def analyze_exam(self, payload: AnalysisInput) -> AnalysisResult:
        """按输入学科生成结构化假数据。"""
        subject_inputs = payload.subjects or self._default_subjects(payload)

        subjects: list[SubjectScore] = []
        weak_points: list[WeakPoint] = []

        for subject in subject_inputs:
            template = self._template_for(subject.subject_code)
            rnd = random.Random(self._seed(payload.exam.exam_id, subject.subject_code))

            full_score = float(subject.full_score or template.get("full_score") or 100)
            if subject.score is not None:
                score = float(subject.score)
            else:
                score = self._jitter(float(template.get("score") or full_score * 0.7), full_score, rnd)

            subject_name = subject.subject_name or template.get("subject_name") or subject.subject_code
            subject_weak = self._build_weak_points(template, subject.subject_code, subject_name, rnd)

            subjects.append(
                SubjectScore(
                    subject_code=subject.subject_code,
                    subject_name=subject_name,
                    score=score,
                    full_score=full_score,
                    rate=round(score / full_score, 4) if full_score else 0.0,
                    weak_module_names=self._weak_module_names(template, subject_weak),
                )
            )
            weak_points.extend(subject_weak)

        # 失分点数量收敛到合理区间：优先保留 severity 高的
        if len(weak_points) > MAX_WEAK_POINTS:
            ordered = sorted(
                weak_points,
                key=lambda item: (_SEVERITY_ORDER.get(item.severity.value, 1), -item.lost_score),
            )
            weak_points = ordered[:MAX_WEAK_POINTS]
        elif len(weak_points) < MIN_WEAK_POINTS and subjects:
            # 学科过少时补足到 MIN_WEAK_POINTS，保证页面有足够内容
            weak_points = self._pad_weak_points(weak_points, subjects, payload.exam.exam_id)

        for index, point in enumerate(weak_points, start=1):
            point.id = f"wp{index}"

        plan = self._build_plan(weak_points)
        prompt_chars = len(payload.exam.name) + sum(len(s.paper_text) for s in subject_inputs)

        return AnalysisResult(
            subjects=subjects,
            weak_points=weak_points,
            improvement_plan=plan,
            model_name=str(self._sample.get("model_name") or self.model_name),
            prompt_tokens=max(prompt_chars // 3, 1),
            completion_tokens=sum(len(p.suggestion) for p in weak_points) // 3 + 200,
        )

    async def extract_text(self, image_urls: list[str], *, subject_name: str = "", hint: str = "") -> str:
        """返回占位题目文本，让「导入 -> OCR -> 分析」链路能完整跑通。"""
        label = subject_name or "未知学科"
        lines = [
            f"【Mock OCR】{label} 共收到 {len(image_urls)} 张图片。",
            "以下为占位题目文本，接入 DASHSCOPE_API_KEY 后将由 qwen-vl-max 真实识别题干与学生作答：",
            "",
            f"1.（8 分）{label}单选题：下列各选项中，说法正确的一项是（  ）",
            "2.（12 分）填空题：请补充完整下列结论，并说明依据。",
            "3.（10 分）解答题：结合材料，写出完整的推导过程。",
            "4.（20 分）综合题：分析上述现象产生的原因，并给出你的解决方案。",
        ]
        if hint:
            lines.append("")
            lines.append(f"（附加提示：{hint}）")
        return "\n".join(lines)

    # ------------------------------------------------------------ 内部构造
    def _default_subjects(self, payload: AnalysisInput) -> list[SubjectInput]:
        """输入没有指定学科时，用模板里的 3 个学科兜底。"""
        subjects_conf = self._sample.get("subjects", {})
        result: list[SubjectInput] = []
        for code, conf in subjects_conf.items():
            if code == "_default":
                continue
            result.append(
                SubjectInput(
                    subject_code=code,
                    subject_name=str(conf.get("subject_name") or code),
                    full_score=float(conf.get("full_score") or 100),
                )
            )
        if not result:
            result.append(SubjectInput(subject_code="comprehensive", subject_name="综合", full_score=100))
        return result

    def _build_weak_points(
        self,
        template: dict[str, Any],
        subject_code: str,
        subject_name: str,
        rnd: random.Random,
    ) -> list[WeakPoint]:
        """按学科模板构造失分点。"""
        raw_points: list[dict[str, Any]] = list(template.get("weak_points") or [])
        points: list[WeakPoint] = []
        for raw in raw_points:
            evidence = raw.get("evidence") or {}
            lost = float(raw.get("lost_score") or 0)
            lost = max(1.0, round(lost + rnd.choice([-1.0, 0.0, 1.0]), 1))
            points.append(
                WeakPoint(
                    id="wp",
                    subject_code=subject_code,
                    subject_name=subject_name,
                    module=str(raw.get("module") or ""),
                    chapter=str(raw.get("chapter") or ""),
                    knowledge_point=str(raw.get("knowledge_point") or ""),
                    severity=raw.get("severity") or "medium",
                    lost_score=lost,
                    evidence=WeakPointEvidence(
                        question_no=str(evidence.get("question_no") or ""),
                        question_text=str(evidence.get("question_text") or ""),
                        student_answer=str(evidence.get("student_answer") or ""),
                        correct_answer=str(evidence.get("correct_answer") or ""),
                        error_type=str(evidence.get("error_type") or ""),
                    ),
                    suggestion=str(raw.get("suggestion") or ""),
                )
            )
        return points

    @staticmethod
    def _weak_module_names(template: dict[str, Any], points: list[WeakPoint]) -> list[str]:
        """薄弱模块名：优先用模板声明，其次从失分点归纳。"""
        declared = template.get("weak_module_names")
        if isinstance(declared, list) and declared:
            return [str(item) for item in declared]
        names: list[str] = []
        for point in points:
            if point.module and point.module not in names:
                names.append(point.module)
        return names

    def _pad_weak_points(
        self,
        existing: list[WeakPoint],
        subjects: list[SubjectScore],
        exam_id: str,
    ) -> list[WeakPoint]:
        """学科较少时，用兜底模板补足失分点数量。"""
        points = list(existing)
        fallback_points = _FALLBACK_SUBJECT["weak_points"]
        for subject in subjects:
            if len(points) >= MIN_WEAK_POINTS:
                break
            rnd = random.Random(self._seed(exam_id, f"pad:{subject.subject_code}"))
            for raw in fallback_points:
                if len(points) >= MIN_WEAK_POINTS:
                    break
                evidence = raw["evidence"]
                points.append(
                    WeakPoint(
                        id="wp",
                        subject_code=subject.subject_code,
                        subject_name=subject.subject_name,
                        module=str(raw["module"]),
                        chapter=str(raw["chapter"]),
                        knowledge_point=str(raw["knowledge_point"]),
                        severity=raw["severity"],
                        lost_score=max(1.0, float(raw["lost_score"]) + rnd.choice([-1.0, 0.0, 1.0])),
                        evidence=WeakPointEvidence(**evidence),
                        suggestion=str(raw["suggestion"]),
                    )
                )
        return points

    def _build_plan(self, weak_points: list[WeakPoint]) -> ImprovementPlan:
        """基于模板构造提升方案，并把优先顺序替换成本次真实的薄弱模块。"""
        template = self._sample.get("improvement_plan") or _FALLBACK_PLAN

        priority: list[str] = []
        for point in sorted(
            weak_points, key=lambda item: (_SEVERITY_ORDER.get(item.severity.value, 1), -item.lost_score)
        ):
            name = point.module or point.knowledge_point
            if name and name not in priority:
                priority.append(name)

        steps = [
            ImprovementStep(
                title=str(step.get("title") or ""),
                detail=str(step.get("detail") or ""),
                estimated_hours=float(step.get("estimated_hours") or 0),
                resources=[str(item) for item in (step.get("resources") or [])],
            )
            for step in (template.get("steps") or [])
        ]
        weekly = [
            WeeklyPlanItem(
                week=int(item.get("week") or 1),
                focus=str(item.get("focus") or ""),
                target=str(item.get("target") or ""),
            )
            for item in (template.get("weekly_plan") or [])
        ]
        return ImprovementPlan(
            summary=str(template.get("summary") or ""),
            priority_order=priority[:5] or [str(item) for item in (template.get("priority_order") or [])],
            steps=steps,
            weekly_plan=weekly,
        )
