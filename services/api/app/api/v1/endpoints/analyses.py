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
from app.core.config import settings
from app.core.errors import NotFoundError
from app.core.response import EnvelopeRoute
from app.models.analysis import Analysis
from app.schemas.analysis import (
    AnalysisCreate,
    AnalysisCreated,
    AnalysisDetail,
    AnalysisPage,
    UnlockStageRequest,
    UnlockStageResult,
    build_stages,
)
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
    """返回完整分析结果与三个阶段的解锁状态。

    未解锁的阶段 `content` 为 `null`——内容不下发到客户端，避免绕过广告直接读取。
    """
    analysis = await db.scalar(
        select(Analysis).where(Analysis.id == analysis_id, Analysis.user_id == user.id)
    )
    if analysis is None:
        raise NotFoundError("分析记录不存在", detail={"analysis_id": analysis_id})
    stages = build_stages(
        analysis,
        ad_requirements=settings.stage_ad_requirements,
        stage_names=settings.stage_names,
    )
    return AnalysisDetail.from_model(analysis, stages=stages)


@router.post(
    "/{analysis_id}/stages/{stage}/unlock",
    response_model=UnlockStageResult,
    summary="上报一次激励视频观看（累计够次数即解锁该阶段）",
)
async def unlock_stage(
    analysis_id: str,
    stage: int,
    payload: UnlockStageRequest,
    user: CurrentUser,
    db: DbSession,
) -> UnlockStageResult:
    """看广告解锁某个阶段。

    阶段 1 / 2 / 3 分别需要 1 / 2 / 2 次（由 `STAGE_AD_REQUIREMENTS` 配置）。
    同一个 `event_id` 重复上报是幂等的，不会重复计数。
    """
    return await analysis_service.unlock_stage_by_ad(
        db, user=user, analysis_id=analysis_id, stage=stage, payload=payload
    )


@router.post(
    "/{analysis_id}/stages/{stage}/unlock-by-credit",
    response_model=UnlockStageResult,
    summary="用点数跳过广告解锁该阶段",
)
async def unlock_stage_with_credit(
    analysis_id: str,
    stage: int,
    user: CurrentUser,
    db: DbSession,
) -> UnlockStageResult:
    """消耗点数直接解锁某个阶段；余额不足返回 40200 且不产生任何副作用。"""
    return await analysis_service.unlock_stage_by_credit(
        db, user=user, analysis_id=analysis_id, stage=stage
    )


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
