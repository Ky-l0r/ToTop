"""趋势端点：把历次考试的得分率整理成折线图数据。

数据来源与口径：

- 每个点代表一次考试，`points` 按 `exam_date` **升序**（前端直接作为横轴）；
- 各科 `rate` 与 `total_rate` 都是 **0~1 的得分率**（分数 ÷ 满分），不同满分的学科才能同图比较；
- 优先使用答题卡上已录入的分数；若该考试还没有分数（只有分析结果），则退回用最近一次
  成功分析的各科得分率；
- **缺考学科不会出现在 `subjects` 字典里**（前端据此跳过该点、折线断开）；
- 完全没有分数数据的考试不会产生数据点（避免在图上画出误导性的 0）。
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Query
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.core.response import EnvelopeRoute
from app.models.analysis import Analysis
from app.models.enums import TaskStatus
from app.models.exam import AnswerSheet, Exam, Subject
from app.schemas.common import clamp_rate
from app.schemas.misc import TrendData, TrendPoint, TrendSubject
from app.schemas.analysis import AnalysisDetail

logger = logging.getLogger("app.api.trends")

router = APIRouter(route_class=EnvelopeRoute, prefix="/trends", tags=["trends"])


@router.get("", response_model=TrendData, summary="成绩趋势")
async def read_trends(
    user: CurrentUser,
    db: DbSession,
    limit: Annotated[int, Query(ge=1, le=100, description="最多返回多少次考试")] = 10,
    subjects: Annotated[str | None, Query(description="只看这些学科，逗号分隔，如 math,chinese")] = None,
) -> TrendData:
    """返回最近 `limit` 次考试的趋势数据（按日期升序）。"""
    wanted = _parse_subject_filter(subjects)

    # 先取最近 limit 次考试（按日期倒序），再反转成升序输出
    exams = list(
        await db.scalars(
            select(Exam)
            .where(Exam.user_id == user.id)
            .order_by(Exam.exam_date.desc(), Exam.created_at.desc(), Exam.id.desc())
            .limit(limit)
        )
    )
    if not exams:
        return TrendData(points=[], subjects=[])

    exams.sort(key=lambda exam: (exam.exam_date, exam.created_at, exam.id))
    exam_ids = [exam.id for exam in exams]

    sheets = list(await db.scalars(select(AnswerSheet).where(AnswerSheet.exam_id.in_(exam_ids))))
    sheets_by_exam: dict[str, list[AnswerSheet]] = {}
    for sheet in sheets:
        sheets_by_exam.setdefault(sheet.exam_id, []).append(sheet)

    # 兜底数据源：最新一次成功的分析（升序遍历，后写入的覆盖前面的）
    latest_analysis: dict[str, AnalysisDetail] = {}
    for analysis in await db.scalars(
        select(Analysis)
        .where(Analysis.exam_id.in_(exam_ids), Analysis.status == TaskStatus.SUCCEEDED)
        .order_by(Analysis.created_at.asc(), Analysis.id.asc())
    ):
        latest_analysis[analysis.exam_id] = AnalysisDetail.from_model(analysis)

    points: list[TrendPoint] = []
    used_codes: list[str] = []

    for exam in exams:
        rates: dict[str, float] = {}
        total_score = 0.0
        total_full_score = 0.0

        for sheet in sheets_by_exam.get(exam.id, []):
            if sheet.score is None or not sheet.full_score:
                continue  # 未录入分数（缺考 / 未批改）→ 不参与该点
            rates[sheet.subject_code] = clamp_rate(float(sheet.score) / float(sheet.full_score))
            total_score += float(sheet.score)
            total_full_score += float(sheet.full_score)

        if rates:
            total_rate = clamp_rate(total_score / total_full_score) if total_full_score else 0.0
        else:
            detail = latest_analysis.get(exam.id)
            if detail is None or not detail.subjects:
                continue  # 没有任何分数数据 → 不产生数据点
            for subject in detail.subjects:
                rates[subject.subject_code] = clamp_rate(subject.rate)
            total_rate = clamp_rate(detail.score_rate)

        if wanted is not None:
            rates = {code: rate for code, rate in rates.items() if code in wanted}
            if not rates:
                continue

        for code in rates:
            if code not in used_codes:
                used_codes.append(code)

        points.append(
            TrendPoint(
                exam_id=exam.id,
                exam_name=exam.name,
                exam_date=exam.exam_date,
                total_rate=total_rate,
                subjects=rates,
            )
        )

    return TrendData(points=points, subjects=await _subject_meta(db, used_codes))


def _parse_subject_filter(raw: str | None) -> set[str] | None:
    """解析 `subjects=math,chinese` 参数；未传时返回 `None`（不过滤）。"""
    if not raw:
        return None
    codes = {item.strip() for item in raw.split(",") if item.strip()}
    return codes or None


async def _subject_meta(db: DbSession, codes: list[str]) -> list[TrendSubject]:
    """把学科 code 补上中文名，并按字典表顺序排序。"""
    if not codes:
        return []

    rows = (await db.execute(select(Subject.code, Subject.name, Subject.sort))).all()
    name_map = {str(code): str(name) for code, name, _sort in rows}
    sort_map = {str(code): int(sort or 0) for code, _name, sort in rows}

    items = [TrendSubject(code=code, name=name_map.get(code, code)) for code in codes]
    items.sort(key=lambda item: (sort_map.get(item.code, 9999), item.code))
    return items
