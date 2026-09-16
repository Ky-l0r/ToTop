"""考试端点：考试 CRUD + 试卷（题目）/ 答题卡登记。

登记试卷或答题卡时：

1. 写入业务表（`papers` / `answer_sheets`，状态 `pending`）；
2. 建一条 `import_tasks` 记录并把 `task_id` 返回给前端；
3. 用 `asyncio.create_task` 派发 OCR 后台协程（`_ocr_paper` / `_ocr_answer_sheet`），
   前端通过 `GET /tasks/{task_id}` 轮询进度。

**为什么 OCR 放到后台**：多张试卷图片的视觉识别需要数十秒，同步等待会拖垮客户端体验；
后台协程使用独立数据库会话，异常会写进 `ImportTask.error`，绝不静默。
"""

from __future__ import annotations

import logging
import re

from fastapi import APIRouter
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import CurrentUser, DbSession, PageParams
from app.api.v1.endpoints.meta import subject_name as subject_name_of
from app.core.errors import ForbiddenError, NotFoundError
from app.core.response import EnvelopeRoute
from app.db.session import session_scope
from app.models.analysis import Analysis, ImportTask
from app.models.enums import TaskKind, TaskStatus
from app.models.exam import AnswerSheet, Exam, Paper
from app.models.user import User
from app.providers.ai import get_ai_provider
from app.schemas.common import OkResult
from app.schemas.exam import (
    AnalysisBrief,
    AnswerSheetCreate,
    AnswerSheetCreateResult,
    ExamCreate,
    ExamDetail,
    ExamOut,
    ExamPage,
    PaperCreate,
    PaperCreateResult,
)
from app.services.analysis import load_images_as_data_urls
from app.tasks import queue

logger = logging.getLogger("app.api.exams")

router = APIRouter(route_class=EnvelopeRoute, prefix="/exams", tags=["exams"])

# 题目编号匹配（如「1.（8 分）」「12、」「18(1)」）
_QUESTION_NO_PATTERN = re.compile(r"^\s*\d{1,3}\s*[.、)）]")


@router.get("", response_model=ExamPage, summary="考试列表")
async def list_exams(page: PageParams, user: CurrentUser, db: DbSession) -> ExamPage:
    """按考试日期倒序分页返回；同时给出已录入学科数与最新分析状态。"""
    total = int(await db.scalar(select(func.count()).select_from(Exam).where(Exam.user_id == user.id)) or 0)
    exams = list(
        await db.scalars(
            select(Exam)
            .where(Exam.user_id == user.id)
            .order_by(Exam.exam_date.desc(), Exam.created_at.desc(), Exam.id.desc())
            .offset(page.offset)
            .limit(page.limit)
        )
    )
    if not exams:
        return ExamPage(items=[], total=total)

    exam_ids = [exam.id for exam in exams]
    subject_counts = await _subject_counts(db, exam_ids)
    analysis_status = await _latest_analysis_status(db, exam_ids)

    items: list[ExamOut] = []
    for exam in exams:
        item = ExamOut.model_validate(exam)
        item.subject_count = subject_counts.get(exam.id, 0)
        item.analysis_status = analysis_status.get(exam.id, "none")
        items.append(item)
    return ExamPage(items=items, total=total)


@router.post("", response_model=ExamOut, summary="创建考试")
async def create_exam(payload: ExamCreate, user: CurrentUser, db: DbSession) -> ExamOut:
    """新建一次考试（试卷与答题卡都挂在它下面）。"""
    exam = Exam(
        user_id=user.id,
        name=payload.name,
        exam_date=payload.exam_date,
        grade=payload.grade,
        exam_type=payload.exam_type,
        total_full_score=payload.total_full_score,
        note=(payload.note or "").strip(),
    )
    db.add(exam)
    await db.commit()
    logger.info("用户 %s 创建考试 %s（%s）", user.id, exam.id, exam.name)
    return ExamOut.model_validate(exam)


@router.get("/{exam_id}", response_model=ExamDetail, summary="考试详情")
async def read_exam(exam_id: str, user: CurrentUser, db: DbSession) -> ExamDetail:
    """考试详情：含 `papers[]`、`answer_sheets[]` 与最新分析摘要。"""
    exam = await _get_owned_exam(db, exam_id=exam_id, user=user)

    detail = ExamDetail.model_validate(exam)
    detail.note = exam.note or ""
    detail.subject_count = len({sheet.subject_code for sheet in exam.answer_sheets})
    detail.analysis_status = "none"

    latest = await db.scalar(
        select(Analysis)
        .where(Analysis.exam_id == exam.id)
        .order_by(Analysis.created_at.desc(), Analysis.id.desc())
        .limit(1)
    )
    if latest is not None:
        detail.analysis_status = latest.status.value
        detail.latest_analysis = AnalysisBrief.model_validate(latest)
    return detail


@router.delete("/{exam_id}", response_model=OkResult, summary="删除考试")
async def delete_exam(exam_id: str, user: CurrentUser, db: DbSession) -> OkResult:
    """删除考试，并级联删除其试卷、答题卡、分析与导入任务。"""
    exam = await _get_owned_exam(db, exam_id=exam_id, user=user)
    await db.delete(exam)
    await db.commit()
    logger.info("用户 %s 删除考试 %s", user.id, exam_id)
    return OkResult(ok=True)


@router.post("/{exam_id}/papers", response_model=PaperCreateResult, summary="登记试卷（题目）")
async def create_paper(
    exam_id: str,
    payload: PaperCreate,
    user: CurrentUser,
    db: DbSession,
) -> PaperCreateResult:
    """把已上传的图片 / PDF 登记为一份试卷，并派发 OCR 识别任务。"""
    exam = await _get_owned_exam(db, exam_id=exam_id, user=user)

    paper = Paper(
        exam_id=exam.id,
        subject_code=payload.subject_code,
        title=payload.title or f"{subject_name_of(payload.subject_code)}试卷",
        source_type=payload.source_type,
        object_keys=list(payload.object_keys),
        ocr_status=TaskStatus.PENDING,
    )
    db.add(paper)
    await db.flush()

    task = await queue.create_task_record(
        db,
        user_id=user.id,
        kind=TaskKind.PAPER,
        exam_id=exam.id,
        message="试卷已登记，排队进行文字识别",
        result_ref=paper.id,
    )
    await db.commit()

    queue.spawn(_ocr_paper(paper_id=paper.id, task_id=task.id), name=f"ocr:paper:{paper.id}")
    return PaperCreateResult(paper_id=paper.id, task_id=task.id)


@router.post("/{exam_id}/answer-sheets", response_model=AnswerSheetCreateResult, summary="登记答题卡")
async def create_answer_sheet(
    exam_id: str,
    payload: AnswerSheetCreate,
    user: CurrentUser,
    db: DbSession,
) -> AnswerSheetCreateResult:
    """把已上传的答题卡图片登记到考试，并派发 OCR 识别任务。"""
    exam = await _get_owned_exam(db, exam_id=exam_id, user=user)

    paper_id = payload.paper_id
    if paper_id:
        paper = await db.get(Paper, paper_id)
        if paper is None or paper.exam_id != exam.id:
            raise NotFoundError("关联的试卷不存在", detail={"paper_id": paper_id})

    sheet = AnswerSheet(
        exam_id=exam.id,
        paper_id=paper_id,
        subject_code=payload.subject_code,
        source_type=payload.source_type,
        is_graded=payload.is_graded,
        object_keys=list(payload.object_keys),
        ocr_status=TaskStatus.PENDING,
        score=payload.score,
        full_score=payload.full_score,
    )
    db.add(sheet)
    await db.flush()

    task = await queue.create_task_record(
        db,
        user_id=user.id,
        kind=TaskKind.ANSWER_SHEET,
        exam_id=exam.id,
        message="答题卡已登记，排队进行识别",
        result_ref=sheet.id,
    )
    await db.commit()

    queue.spawn(
        _ocr_answer_sheet(sheet_id=sheet.id, task_id=task.id),
        name=f"ocr:answer_sheet:{sheet.id}",
    )
    return AnswerSheetCreateResult(answer_sheet_id=sheet.id, task_id=task.id)


# ------------------------------------------------------------------ 后台协程


async def _ocr_paper(*, paper_id: str, task_id: str) -> None:
    """识别试卷题目文本并写回 `papers.ocr_text`。"""
    try:
        await queue.update_task(task_id, status=TaskStatus.RUNNING, progress=15, message="正在识别题目文字…")
        async with session_scope() as db:
            paper = await db.get(Paper, paper_id)
            if paper is None:
                await queue.update_task(
                    task_id, status=TaskStatus.FAILED, progress=100, message="试卷不存在", error="paper not found"
                )
                return

            label = subject_name_of(paper.subject_code)
            images = await load_images_as_data_urls(list(paper.object_keys or []))
            text = await get_ai_provider().extract_text(
                images,
                subject_name=label,
                hint="这是一份试卷，请识别题号、题干与分值。",
            )
            text = (text or "").strip()

            if text:
                paper.ocr_text = text
                paper.question_count = _count_questions(text)
                paper.ocr_status = TaskStatus.SUCCEEDED
                await db.commit()
                await queue.update_task(
                    task_id,
                    status=TaskStatus.SUCCEEDED,
                    progress=100,
                    message=f"识别完成，共 {paper.question_count} 道题",
                    result_ref=paper.id,
                )
            else:
                paper.ocr_status = TaskStatus.FAILED
                await db.commit()
                await queue.update_task(
                    task_id,
                    status=TaskStatus.FAILED,
                    progress=100,
                    message="未能识别出题目文字",
                    error="OCR 未返回文本，请上传更清晰的图片",
                    result_ref=paper.id,
                )
    except Exception as exc:  # noqa: BLE001 —— 后台任务必须自己兜住异常
        logger.exception("试卷 OCR 任务 %s 失败", task_id)
        await queue.mark_failed(task_id, f"{type(exc).__name__}: {exc}")


async def _ocr_answer_sheet(*, sheet_id: str, task_id: str) -> None:
    """识别答题卡文字与作答要点，结果写入 `answer_sheets.ocr_result`。"""
    try:
        await queue.update_task(task_id, status=TaskStatus.RUNNING, progress=15, message="正在识别答题卡…")
        async with session_scope() as db:
            sheet = await db.get(AnswerSheet, sheet_id)
            if sheet is None:
                await queue.update_task(
                    task_id, status=TaskStatus.FAILED, progress=100, message="答题卡不存在", error="answer sheet not found"
                )
                return

            label = subject_name_of(sheet.subject_code)
            images = await load_images_as_data_urls(list(sheet.object_keys or []))
            text = await get_ai_provider().extract_text(
                images,
                subject_name=label,
                hint="这是一张学生答题卡，请识别题号与学生的作答内容。",
            )
            text = (text or "").strip()

            if text:
                sheet.ocr_result = {"text": text, "image_count": len(images), "question_count": _count_questions(text)}
                sheet.ocr_status = TaskStatus.SUCCEEDED
                await db.commit()
                await queue.update_task(
                    task_id,
                    status=TaskStatus.SUCCEEDED,
                    progress=100,
                    message="识别完成",
                    result_ref=sheet.id,
                )
            else:
                sheet.ocr_status = TaskStatus.FAILED
                await db.commit()
                await queue.update_task(
                    task_id,
                    status=TaskStatus.FAILED,
                    progress=100,
                    message="未能识别出答题卡内容",
                    error="OCR 未返回文本，请上传更清晰的图片",
                    result_ref=sheet.id,
                )
    except Exception as exc:  # noqa: BLE001
        logger.exception("答题卡 OCR 任务 %s 失败", task_id)
        await queue.mark_failed(task_id, f"{type(exc).__name__}: {exc}")


# ---------------------------------------------------------------------- 内部工具


async def _get_owned_exam(db: AsyncSession, *, exam_id: str, user: User) -> Exam:
    """取出考试并校验归属。

    :raises NotFoundError: 考试不存在（40400）
    :raises ForbiddenError: 考试属于其他用户（40104）
    """
    exam = await db.get(Exam, exam_id)
    if exam is None:
        raise NotFoundError("考试不存在", detail={"exam_id": exam_id})
    if exam.user_id != user.id:
        raise ForbiddenError("无权访问该考试", detail={"exam_id": exam_id})
    return exam


async def _subject_counts(db: AsyncSession, exam_ids: list[str]) -> dict[str, int]:
    """批量统计每个考试已录入的学科数（按答题卡的 subject_code 去重）。"""
    rows = await db.execute(
        select(AnswerSheet.exam_id, AnswerSheet.subject_code)
        .where(AnswerSheet.exam_id.in_(exam_ids))
        .distinct()
    )
    counts: dict[str, int] = {}
    for exam_id, _code in rows.all():
        counts[exam_id] = counts.get(exam_id, 0) + 1
    return counts


async def _latest_analysis_status(db: AsyncSession, exam_ids: list[str]) -> dict[str, str]:
    """批量取每个考试「最新一条分析」的状态（按创建时间升序覆盖，结果即最新）。"""
    rows = await db.execute(
        select(Analysis.exam_id, Analysis.status)
        .where(Analysis.exam_id.in_(exam_ids))
        .order_by(Analysis.created_at.asc(), Analysis.id.asc())
    )
    status: dict[str, str] = {}
    for exam_id, value in rows.all():
        status[exam_id] = value.value
    return status


def _count_questions(text: str) -> int:
    """粗略统计题目数量（识别文本中以数字编号开头的行数）。"""
    return sum(1 for line in text.splitlines() if _QUESTION_NO_PATTERN.match(line))
