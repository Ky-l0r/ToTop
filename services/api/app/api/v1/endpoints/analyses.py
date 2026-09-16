"""分析端点：发起分析、查询结果、历史列表。

- `POST /analyses`：原子扣费 + 建记录 + 派发后台分析（详见 `app.services.analysis`）；
- `GET /analyses/{id}`：完整结果（前端轮询 `status` 直到 succeeded / failed）；
- `GET /analyses`：按考试或全部分析历史分页。
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Query
from sqlalchemy import func, select

from app.api.deps import CurrentUser, DbSession, PageParams
from app.core.errors import NotFoundError
from app.core.response import EnvelopeRoute
from app.models.analysis import Analysis
from app.schemas.analysis import AnalysisCreate, AnalysisCreated, AnalysisDetail, AnalysisPage
from app.services import analysis as analysis_service

logger = logging.getLogger("app.api.analyses")

router = APIRouter(route_class=EnvelopeRoute, prefix="/analyses", tags=["analyses"])


@router.post("", response_model=AnalysisCreated, summary="发起 AI 失分分析")
async def create_analysis(payload: AnalysisCreate, user: CurrentUser, db: DbSession) -> AnalysisCreated:
    """扣费并异步分析；点数不足返回 40200 且不产生任何副作用。"""
    result = await analysis_service.create_analysis(db, user=user, payload=payload)
    logger.info("用户 %s 发起分析 %s（考试 %s）", user.id, result.analysis_id, payload.exam_id)
    return result


@router.get("/{analysis_id}", response_model=AnalysisDetail, summary="分析结果详情")
async def read_analysis(analysis_id: str, user: CurrentUser, db: DbSession) -> AnalysisDetail:
    """返回完整分析结果；未完成时各字段为空结构而非报错。"""
    analysis = await db.scalar(
        select(Analysis).where(Analysis.id == analysis_id, Analysis.user_id == user.id)
    )
    if analysis is None:
        raise NotFoundError("分析记录不存在", detail={"analysis_id": analysis_id})
    return AnalysisDetail.from_model(analysis)


@router.get("", response_model=AnalysisPage, summary="分析历史列表")
async def list_analyses(
    page: PageParams,
    user: CurrentUser,
    db: DbSession,
    exam_id: Annotated[str | None, Query(description="按考试筛选")] = None,
) -> AnalysisPage:
    """按创建时间倒序分页返回分析记录（可按考试筛选）。"""
    conditions = [Analysis.user_id == user.id]
    if exam_id:
        conditions.append(Analysis.exam_id == exam_id)

    total = int(await db.scalar(select(func.count()).select_from(Analysis).where(*conditions)) or 0)
    rows = await db.scalars(
        select(Analysis)
        .where(*conditions)
        .order_by(Analysis.created_at.desc(), Analysis.id.desc())
        .offset(page.offset)
        .limit(page.limit)
    )
    return AnalysisPage(
        items=[AnalysisDetail.from_model(item) for item in rows],
        total=total,
    )
