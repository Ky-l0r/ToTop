"""AI 失分分析编排。

一次分析的完整链路（对应 `docs/01-技术方案.md` 5.2 节）：

```
POST /analyses
  ├─ 1. 校验考试归属（非本人 → 403）与答题卡（没有答题卡 → 40050）
  ├─ 2. 原子扣费：UPDATE users SET credits = credits - :cost WHERE id = ? AND credits >= :cost
  │      影响行数为 0 → 抛 40200「点数不足」，不产生任何副作用
  ├─ 3. 同一事务内创建 Analysis(status=pending) 并写账本（幂等键 analysis:{analysis_id}）
  ├─ 4. 派发后台协程：组装题目文本 + 答题卡图片 → 调 AI Provider
  ├─ 5. 结果 Schema 校验 → 归一化（得分率、失分点编号）→ 落库 → 回写各科分数 → status=succeeded
  └─ 失败：status=failed + error（异常绝不静默），并写一条 refund 退还流水（幂等键 refund:{analysis_id}）
```

事务边界：
- 扣费与建 `Analysis` 必须同事务，保证「要么扣了钱有记录，要么什么都没发生」；
- 后台任务使用独立会话（`session_scope`），与请求会话完全隔离。

Windows 下的注意点：后台协程由 `app.tasks.queue.spawn` 用 `asyncio.create_task` 派发，
依赖运行中的事件循环（uvicorn 的 lifespan 内部即为该循环），因此**不会**出现跨事件循环复用
SQLAlchemy 异步连接的问题。
"""

from __future__ import annotations

import base64
import logging
import mimetypes
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.errors import AppError, ErrorCode, ForbiddenError, NotFoundError, ParamError
from app.core.utils import new_id, utcnow
from app.db.session import session_scope
from app.models.analysis import Analysis
from app.models.credits import AdEvent
from app.models.enums import AdPlatform, AdType, CreditReason, TaskStatus
from app.models.exam import AnswerSheet, Exam, KnowledgeModule, Paper, Subject
from app.models.user import User
from app.providers.ai import get_ai_provider
from app.providers.ai.base import AnalysisInput, ExamInput, SubjectInput
from app.providers.storage import get_storage_provider
from app.schemas.analysis import (
    AnalysisCreated,
    AnalysisCreate,
    AnalysisResult,
    UnlockStageRequest,
    UnlockStageResult,
    WeakPoint,
    build_stages,
    new_stage_state,
    recompute_subject_rate,
)
from app.schemas.common import clamp_rate
from app.services.credits import add_credits, get_balance, spend_credits
from app.tasks import queue

logger = logging.getLogger("app.services.analysis")

__all__ = [
    "build_analysis_input",
    "create_analysis",
    "load_images_as_data_urls",
    "run_analysis",
    "unlock_stage_by_ad",
    "unlock_stage_by_credit",
]

# 进行中的分析状态（同一考试不重复扣费）
_ACTIVE_STATUS = (TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.SUCCEEDED)


async def create_analysis(db: AsyncSession, *, user: User, payload: AnalysisCreate) -> AnalysisCreated:
    """发起一次分析：原子扣费 + 建记录 + 派发后台任务。

    `force=False`（默认）时，若该考试已有进行中或已成功的分析，直接复用并**不重复扣费**；
    `force=True` 时强制重新分析（**再次扣费**）。

    :raises CreditNotEnoughError: 点数不足（40200），此时不产生任何副作用
    """
    exam = await db.get(Exam, payload.exam_id)
    if exam is None:
        raise NotFoundError("考试不存在", detail={"exam_id": payload.exam_id})
    if exam.user_id != user.id:
        raise ForbiddenError("无权访问该考试", detail={"exam_id": exam.id})

    answer_sheet = await _resolve_answer_sheet(db, exam=exam, answer_sheet_id=payload.answer_sheet_id)

    if not payload.force:
        reusable = await db.scalar(
            select(Analysis)
            .where(Analysis.exam_id == exam.id, Analysis.status.in_(_ACTIVE_STATUS))
            .order_by(Analysis.created_at.desc(), Analysis.id.desc())
            .limit(1)
        )
        if reusable is not None:
            balance = await get_balance(db, user.id) or 0
            logger.info("考试 %s 已有分析 %s（%s），直接复用，不重复扣费", exam.id, reusable.id, reusable.status.value)
            return AnalysisCreated(
                analysis_id=reusable.id,
                status=reusable.status,
                credits_cost=0,
                credits_balance=balance,
            )

    cost = settings.analysis_credit_cost
    analysis_id = new_id()

    # ① 原子扣费：余额不足会直接抛 40200，下面一行都不会执行。
    #    小程序版本 cost 默认为 0（分析免费，结果分三段看广告解锁），此时跳过扣费只读余额。
    if cost > 0:
        change = await spend_credits(
            db,
            user_id=user.id,
            cost=cost,
            reason=CreditReason.ANALYSIS,
            idempotency_key=f"analysis:{analysis_id}",
            ref_type="analysis",
            ref_id=analysis_id,
            remark=f"AI 失分分析：{exam.name}",
        )
        balance_after = change.balance_after
    else:
        balance_after = await get_balance(db, user.id) or 0

    # ② 同一事务内建立分析记录（同时初始化三个阶段的解锁状态）
    db.add(
        Analysis(
            id=analysis_id,
            exam_id=exam.id,
            answer_sheet_id=answer_sheet.id if answer_sheet is not None else None,
            user_id=user.id,
            status=TaskStatus.PENDING,
            credits_cost=cost,
            stage_state=new_stage_state(settings.stage_ad_requirements),
            model_name=get_ai_provider().model_name,
        )
    )
    await db.commit()

    # ③ 派发后台任务（先提交再派发，保证任务一定能读到记录）
    queue.spawn(run_analysis(analysis_id), name=f"analysis:{analysis_id}")

    return AnalysisCreated(
        analysis_id=analysis_id,
        status=TaskStatus.PENDING,
        credits_cost=cost,
        credits_balance=balance_after,
    )


async def run_analysis(analysis_id: str) -> None:
    """后台执行一次分析（由 `app.tasks.queue` 派发）。

    本协程**捕获全部异常**：失败时写 `Analysis.status=failed` + `Analysis.error` 并退还点数，
    绝不会把异常丢给事件循环（否则前端只会看到一直 pending）。
    """
    try:
        async with session_scope() as db:
            analysis = await db.get(Analysis, analysis_id)
            if analysis is None:
                logger.warning("分析 %s 不存在（可能已被删除），跳过执行", analysis_id)
                return

            analysis.status = TaskStatus.RUNNING
            analysis.error = None
            await db.commit()

            try:
                result = await _analyze(db, analysis)
            except Exception as exc:  # noqa: BLE001 —— 任何失败都要落库 + 退费
                await db.rollback()
                await _mark_failed(db, analysis_id=analysis_id, exc=exc)
                return

            await _persist(db, analysis=analysis, result=result)
            logger.info(
                "分析 %s 完成：%s 科、%s 个失分点、得分率 %s",
                analysis_id,
                len(result.subjects),
                len(result.weak_points),
                analysis.score_rate,
            )
    except Exception:  # noqa: BLE001 —— 兜底：连失败落库都失败时至少留下日志
        logger.exception("分析任务 %s 执行过程中出现未处理异常", analysis_id)


# ---------------------------------------------------------------------- 内部实现


async def _resolve_answer_sheet(
    db: AsyncSession,
    *,
    exam: Exam,
    answer_sheet_id: str | None,
) -> AnswerSheet | None:
    """确定本次分析使用的答题卡：显式指定则校验归属，否则取最新一张。"""
    if answer_sheet_id:
        sheet = await db.get(AnswerSheet, answer_sheet_id)
        if sheet is None or sheet.exam_id != exam.id:
            raise NotFoundError("答题卡不存在", detail={"answer_sheet_id": answer_sheet_id})
        return sheet

    sheet = (
        await db.scalars(
            select(AnswerSheet)
            .where(AnswerSheet.exam_id == exam.id)
            .order_by(AnswerSheet.created_at.desc(), AnswerSheet.id.desc())
            .limit(1)
        )
    ).first()
    if sheet is None:
        raise ParamError(
            "该考试还没有答题卡，请先上传答题卡",
            code=ErrorCode.EXAM_HAS_NO_ANSWER_SHEET,
        )
    return sheet


async def _analyze(db: AsyncSession, analysis: Analysis) -> AnalysisResult:
    """组装输入并调用 AI Provider，返回归一化后的结果。"""
    payload = await build_analysis_input(db, analysis)
    if not payload.subjects:
        raise ParamError("该考试没有可用于分析的答题卡", code=ErrorCode.EXAM_HAS_NO_ANSWER_SHEET)

    provider = get_ai_provider()
    result = await provider.analyze_exam(payload)
    return _normalize(result, provider.model_name)


async def build_analysis_input(db: AsyncSession, analysis: Analysis) -> AnalysisInput:
    """把考试 / 试卷 / 答题卡组装成 Provider 需要的输入结构。"""
    exam = await db.get(Exam, analysis.exam_id)
    if exam is None:  # pragma: no cover —— 外键保证存在，防御性分支
        raise NotFoundError("考试不存在", detail={"exam_id": analysis.exam_id})

    subject_names = await _subject_names(db)
    modules = await _modules_by_subject(db)

    sheets = list(
        await db.scalars(
            select(AnswerSheet)
            .where(AnswerSheet.exam_id == exam.id)
            .order_by(AnswerSheet.created_at.asc(), AnswerSheet.id.asc())
        )
    )
    papers: dict[str, Paper] = {}
    for paper in await db.scalars(
        select(Paper).where(Paper.exam_id == exam.id).order_by(Paper.created_at.asc(), Paper.id.asc())
    ):
        papers[paper.subject_code] = paper

    # 同一学科多张答题卡时以最新一张为准
    latest_sheet: dict[str, AnswerSheet] = {}
    for sheet in sheets:
        latest_sheet[sheet.subject_code] = sheet

    image_budget = max(settings.ai_max_images, 0)
    subjects: list[SubjectInput] = []
    for code, sheet in latest_sheet.items():
        keys = list(sheet.object_keys or [])[:image_budget]
        images = await load_images_as_data_urls(keys)
        image_budget -= len(images)
        paper = papers.get(code)
        subjects.append(
            SubjectInput(
                subject_code=code,
                subject_name=subject_names.get(code, code),
                answer_sheet_id=sheet.id,
                is_graded=sheet.is_graded,
                score=sheet.score,
                full_score=sheet.full_score,
                paper_text=(paper.ocr_text if paper is not None else "") or "",
                image_urls=images,
                weak_module_hint=modules.get(code, []),
            )
        )

    return AnalysisInput(
        exam=ExamInput(
            exam_id=exam.id,
            name=exam.name,
            exam_date=exam.exam_date.isoformat(),
            grade=exam.grade or "",
            exam_type=exam.exam_type.value if exam.exam_type is not None else None,
            total_full_score=float(exam.total_full_score or 0),
        ),
        subjects=subjects,
        knowledge_modules=modules,
        extra_note=exam.note or "",
    )


async def load_images_as_data_urls(object_keys: list[str]) -> list[str]:
    """把存储中的图片读成 data URL（读取失败只记日志，不阻断调用方流程）。

    供分析（把答题卡送给视觉模型）与 OCR（识别题目文本）共用。
    """
    if not object_keys:
        return []

    storage = get_storage_provider()
    urls: list[str] = []
    for key in object_keys:
        try:
            data = await storage.read_bytes(key)
        except FileNotFoundError:
            logger.warning("答题卡图片不存在，已跳过：%s", key)
            continue
        except Exception:  # noqa: BLE001 —— 单张图片失败不应该让整次分析失败
            logger.warning("读取答题卡图片失败，已跳过：%s", key, exc_info=True)
            continue
        if not data:
            continue
        mime = mimetypes.guess_type(key)[0] or "image/jpeg"
        urls.append(f"data:{mime};base64,{base64.b64encode(data).decode('ascii')}")
    return urls


async def _subject_names(db: AsyncSession) -> dict[str, str]:
    """学科 code -> 中文名。"""
    rows = (await db.execute(select(Subject.code, Subject.name))).all()
    return {str(code): str(name) for code, name in rows}


async def _modules_by_subject(db: AsyncSession) -> dict[str, list[str]]:
    """学科 code -> 知识模块名列表（用于对齐模型的术语）。"""
    result: dict[str, list[str]] = {}
    for module in await db.scalars(
        select(KnowledgeModule).where(KnowledgeModule.level == 1).order_by(KnowledgeModule.sort)
    ):
        result.setdefault(module.subject_code, []).append(module.name)
    return result


def _normalize(result: AnalysisResult, fallback_model: str) -> AnalysisResult:
    """结果归一化：得分率补算、失分点 ID 唯一化、薄弱模块名兜底。"""
    subjects = [recompute_subject_rate(subject) for subject in result.subjects]

    seen: set[str] = set()
    for index, point in enumerate(result.weak_points, start=1):
        point.id = _unique_point_id(point, index, seen)
        seen.add(point.id)

    if subjects and any(not subject.weak_module_names for subject in subjects):
        modules: dict[str, list[str]] = {subject.subject_code: [] for subject in subjects}
        for point in result.weak_points:
            names = modules.setdefault(point.subject_code, [])
            name = point.module or point.knowledge_point
            if name and name not in names:
                names.append(name)
        for subject in subjects:
            if not subject.weak_module_names:
                subject.weak_module_names = modules.get(subject.subject_code, [])

    if not result.model_name:
        result.model_name = fallback_model
    return result


def _unique_point_id(point: WeakPoint, index: int, seen: set[str]) -> str:
    """保证失分点 ID 在结果内唯一（前端用作列表 key）。"""
    candidate = (point.id or "").strip()
    if not candidate or candidate == "wp" or candidate in seen:
        candidate = f"wp{index}"
    while candidate in seen:  # pragma: no cover —— 极端情况下继续追加后缀
        candidate = f"{candidate}_{index}"
    return candidate


async def _persist(db: AsyncSession, *, analysis: Analysis, result: AnalysisResult) -> None:
    """落库分析结果并回写各科分数。"""
    total_score = round(sum(subject.score for subject in result.subjects), 2)
    full_score = round(sum(subject.full_score for subject in result.subjects), 2)
    score_rate = clamp_rate(total_score / full_score) if full_score else 0.0

    analysis.result = result.model_dump(mode="json")
    analysis.model_name = result.model_name or analysis.model_name
    analysis.prompt_tokens = int(result.prompt_tokens or 0)
    analysis.completion_tokens = int(result.completion_tokens or 0)
    analysis.total_score = total_score
    analysis.full_score = full_score
    analysis.score_rate = score_rate
    analysis.status = TaskStatus.SUCCEEDED
    analysis.error = None
    analysis.finished_at = utcnow()
    await db.flush()

    await _write_back_scores(db, analysis=analysis, result=result)
    await db.commit()


async def _write_back_scores(db: AsyncSession, *, analysis: Analysis, result: AnalysisResult) -> None:
    """把模型判定的各科分数回写到答题卡（只补空缺，不覆盖老师给的分数）。

    这样趋势接口与考试详情都能直接读到分数，无需再依赖分析 JSON。
    """
    scores = {subject.subject_code: subject for subject in result.subjects}
    if not scores:
        return

    sheets = await db.scalars(select(AnswerSheet).where(AnswerSheet.exam_id == analysis.exam_id))
    for sheet in sheets:
        subject = scores.get(sheet.subject_code)
        if subject is None:
            continue
        if sheet.score is None:
            sheet.score = float(subject.score)
        if sheet.full_score is None:
            sheet.full_score = float(subject.full_score)


async def _mark_failed(db: AsyncSession, *, analysis_id: str, exc: Exception) -> None:
    """把分析标记为失败并退还点数（点数退还自身也是幂等的）。"""
    # 回滚后 ORM 对象已失效，重新加载一份可用对象
    analysis = await db.get(Analysis, analysis_id)
    if analysis is None:  # pragma: no cover
        logger.error("分析 %s 失败后记录已不存在，无法写入错误信息：%s", analysis_id, exc)
        return

    message = _describe_error(exc)
    analysis.status = TaskStatus.FAILED
    analysis.error = message
    analysis.finished_at = utcnow()

    if analysis.credits_cost > 0:
        try:
            await add_credits(
                db,
                user_id=analysis.user_id,
                delta=int(analysis.credits_cost),
                reason=CreditReason.REFUND,
                idempotency_key=f"refund:{analysis.id}",
                ref_type="analysis",
                ref_id=analysis.id,
                remark="分析失败，退还点数",
            )
        except Exception:  # noqa: BLE001 —— 退费失败不能掩盖原始错误
            logger.exception("分析 %s 退还点数失败", analysis_id)

    await db.commit()


# ======================================================================
# 分阶段解锁
#
# 三个阶段的内容全部由 `build_stages` 从**同一份分析结果**派生，这里只负责
# 「可见性控制」与幂等：
# - 看激励视频：每次上报累计 1 次，达到阈值即解锁；
# - 用点数：直接扣费解锁，给不愿看广告的用户一条付费路径。
#
# 注意：结果对象一律在 commit **之前**读取。SQLAlchemy 默认 expire_on_commit，
# commit 之后再访问 ORM 属性会在 async 上下文里触发懒加载而报错。
# ======================================================================


def _ensure_stage(stage: int) -> None:
    """校验阶段序号。"""
    if stage not in (1, 2, 3):
        raise ParamError("阶段序号只能是 1、2、3", detail={"stage": stage})


def _coerce_platform(value: str) -> AdPlatform:
    """归一客户端上报的广告平台；未知值记为 mock，不影响幂等判断。"""
    try:
        return AdPlatform(value)
    except ValueError:
        return AdPlatform.MOCK


def _stage_item(analysis: Analysis, stage: int) -> dict[str, Any]:
    """读取某阶段的解锁状态（缺失时给出默认结构）。"""
    state: dict[str, Any] = analysis.stage_state or {}
    item = state.get(str(stage))
    if isinstance(item, dict):
        return dict(item)
    return {"watched": 0, "unlocked": False, "unlocked_at": None, "unlock_method": None}


def _save_stage_item(analysis: Analysis, stage: int, item: dict[str, Any]) -> None:
    """写回某阶段状态。

    JSON 字段必须整体重新赋值——原地修改字典不会被 SQLAlchemy 识别为变更。
    """
    state: dict[str, Any] = dict(analysis.stage_state or {})
    state[str(stage)] = item
    analysis.stage_state = state


def _stage_result(
    analysis: Analysis,
    stage: int,
    *,
    credited: bool,
    duplicated: bool,
    credits_balance: int | None = None,
) -> UnlockStageResult:
    """组装解锁返回体（未解锁时 content 为 null）。"""
    stages = build_stages(
        analysis,
        ad_requirements=settings.stage_ad_requirements,
        stage_names=settings.stage_names,
    )
    current = next((item for item in stages if item.stage == stage), None)
    return UnlockStageResult(
        stage=stage,
        watched=current.watched if current else 0,
        ad_required=current.ad_required if current else settings.stage_ad_required(stage),
        unlocked=bool(current and current.unlocked),
        credited=credited,
        duplicated=duplicated,
        unlock_method=current.unlock_method if current else None,
        content=current.content if current else None,
        credits_balance=credits_balance,
    )


async def _load_analysis(db: AsyncSession, *, user: User, analysis_id: str) -> Analysis:
    """取出属于当前用户的分析记录。"""
    analysis = await db.scalar(
        select(Analysis).where(Analysis.id == analysis_id, Analysis.user_id == user.id)
    )
    if analysis is None:
        raise NotFoundError("分析记录不存在", detail={"analysis_id": analysis_id})
    return analysis


async def unlock_stage_by_ad(
    db: AsyncSession,
    *,
    user: User,
    analysis_id: str,
    stage: int,
    payload: UnlockStageRequest,
) -> UnlockStageResult:
    """上报一次激励视频观看；累计到阈值即解锁该阶段。

    - 同一个 `event_id` 重复上报只计一次（客户端重试安全，返回 `duplicated=true`）；
    - 观看时长低于 `settings.ad_reward_min_seconds` 时抛 40021，不计入进度。
    """
    _ensure_stage(stage)
    analysis = await _load_analysis(db, user=user, analysis_id=analysis_id)

    existing = await db.scalar(select(AdEvent).where(AdEvent.event_id == payload.event_id))
    if existing is not None:
        logger.info("广告事件 %s 已处理过，按幂等返回", payload.event_id)
        return _stage_result(analysis, stage, credited=False, duplicated=True)

    if payload.watched_seconds < settings.ad_reward_min_seconds:
        raise ParamError(
            f"广告观看时长不足（需不少于 {settings.ad_reward_min_seconds} 秒）",
            detail={"watched_seconds": payload.watched_seconds},
        )

    db.add(
        AdEvent(
            user_id=user.id,
            platform=_coerce_platform(payload.platform),
            event_id=payload.event_id,
            ad_type=AdType.REWARD_VIDEO,
            watched_seconds=payload.watched_seconds,
            credited=False,
        )
    )

    item = _stage_item(analysis, stage)
    if not item.get("unlocked"):
        item["watched"] = int(item.get("watched") or 0) + 1
        required = settings.stage_ad_required(stage)
        if item["watched"] >= required:
            item["unlocked"] = True
            item["unlock_method"] = "ad"
            item["unlocked_at"] = utcnow().isoformat()
            logger.info(
                "分析 %s 的%s已解锁（看满 %d 次广告）", analysis.id, settings.stage_name(stage), required
            )
    _save_stage_item(analysis, stage, item)

    await db.flush()
    result = _stage_result(analysis, stage, credited=True, duplicated=False)
    await db.commit()
    return result


async def unlock_stage_by_credit(
    db: AsyncSession,
    *,
    user: User,
    analysis_id: str,
    stage: int,
) -> UnlockStageResult:
    """用点数跳过广告，直接解锁某个阶段（点数不足返回 40200 且不解锁）。"""
    _ensure_stage(stage)
    analysis = await _load_analysis(db, user=user, analysis_id=analysis_id)

    item = _stage_item(analysis, stage)
    if item.get("unlocked"):
        balance = await get_balance(db, user.id)
        return _stage_result(analysis, stage, credited=False, duplicated=True, credits_balance=balance)

    change = await spend_credits(
        db,
        user_id=user.id,
        cost=settings.stage_unlock_credit_cost,
        reason=CreditReason.PURCHASE,
        idempotency_key=f"stage-unlock:{analysis.id}:{stage}",
        ref_type="analysis",
        ref_id=analysis.id,
        remark=f"跳过广告解锁{settings.stage_name(stage)}",
    )

    item.update(
        {
            "watched": settings.stage_ad_required(stage),
            "unlocked": True,
            "unlock_method": "credit",
            "unlocked_at": utcnow().isoformat(),
        }
    )
    _save_stage_item(analysis, stage, item)

    await db.flush()
    result = _stage_result(
        analysis, stage, credited=True, duplicated=False, credits_balance=change.balance_after
    )
    await db.commit()
    return result
    logger.error("分析 %s 失败：%s", analysis_id, message)


def _describe_error(exc: Exception) -> str:
    """把异常转成适合展示给用户 / 排查的错误文案（业务异常带错误码前缀）。"""
    if isinstance(exc, AppError):
        return f"[{exc.code}] {exc.message}"
    return f"{type(exc).__name__}: {exc}"[:2000]
