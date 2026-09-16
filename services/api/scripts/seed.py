"""灌演示数据脚本（幂等，可反复执行）。

用法（在 `services/api` 目录下）::

    .\\.venv\\Scripts\\python.exe scripts\\seed.py
    .\\.venv\\Scripts\\python.exe scripts\\seed.py --reset   # 先删除演示用户及其数据再重灌

产出：

- 1 个演示用户：手机号 `13800008888`、密码 `a123456`、初始 5 点数（并写账本）；
- 6 次考试（日期分布在过去一年内），每次含多学科答题卡与真实分数，用于趋势图；
- 最近一次考试附带 **1 条已完成的分析**（走真实的 `services.analysis` 编排 + Mock Provider，
  因此结果结构与线上完全一致）。

判重规则：考试按「用户 + 名称」判重，分析按「该考试是否已有成功分析」判重，
所以重复执行不会产生重复数据。
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from datetime import date, timedelta
from pathlib import Path

# 允许直接以脚本方式运行（把 services/api 加入 sys.path）
BASE_DIR = Path(__file__).resolve().parents[1]
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from sqlalchemy import delete, func, select  # noqa: E402

from app.api.v1.endpoints.meta import ensure_dictionary  # noqa: E402
from app.core.security import hash_password  # noqa: E402
from app.core.utils import gen_invite_code, utcnow  # noqa: E402
from app.db.session import dispose_engine, init_models, session_scope  # noqa: E402
from app.models.analysis import Analysis, ImportTask  # noqa: E402
from app.models.enums import (  # noqa: E402
    CreditReason,
    ExamType,
    SourceType,
    TaskStatus,
)
from app.models.exam import AnswerSheet, Exam, Paper  # noqa: E402
from app.models.user import User  # noqa: E402
from app.services.analysis import run_analysis  # noqa: E402
from app.services.credits import add_credits  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(levelname)-7s | %(message)s")
logger = logging.getLogger("scripts.seed")

DEMO_PHONE = "13800008888"
DEMO_PASSWORD = "a123456"
DEMO_NICKNAME = "示例同学"
DEMO_CREDITS = 5

# 学科 code -> (名称, 满分)
SUBJECT_FULL_SCORES: dict[str, tuple[str, int]] = {
    "chinese": ("语文", 150),
    "math": ("数学", 150),
    "english": ("英语", 150),
    "physics": ("物理", 100),
    "chemistry": ("化学", 100),
    "biology": ("生物", 90),
}
DEMO_TOTAL_FULL_SCORE = sum(full for _name, full in SUBJECT_FULL_SCORES.values())  # 740

# 6 次考试：days_ago 为相对今天的天数，scores 为各科实际得分
DEMO_EXAMS: tuple[dict[str, object], ...] = (
    {
        "name": "高二上·开学摸底考",
        "days_ago": 330,
        "grade": "高二",
        "exam_type": ExamType.MOCK,
        "scores": {"chinese": 105, "math": 88, "english": 106, "physics": 62, "chemistry": 58, "biology": 68},
    },
    {
        "name": "高二上·第一次月考",
        "days_ago": 300,
        "grade": "高二",
        "exam_type": ExamType.MONTHLY,
        "scores": {"chinese": 108, "math": 92, "english": 110, "physics": 66, "chemistry": 61, "biology": 72},
    },
    {
        "name": "高二上·期中考试",
        "days_ago": 265,
        "grade": "高二",
        "exam_type": ExamType.MIDTERM,
        "scores": {"chinese": 110, "math": 95, "english": 112, "physics": 68, "chemistry": 64, "biology": 74},
    },
    {
        "name": "高二下·第一次月考",
        "days_ago": 180,
        "grade": "高二",
        "exam_type": ExamType.MONTHLY,
        "scores": {"chinese": 112, "math": 99, "english": 115, "physics": 71, "chemistry": 67, "biology": 76},
    },
    {
        "name": "高三上·开学考",
        "days_ago": 62,
        "grade": "高三",
        "exam_type": ExamType.MOCK,
        "scores": {"chinese": 115, "math": 103, "english": 118, "physics": 74, "chemistry": 70, "biology": 79},
    },
    {
        "name": "高三上·第一次月考",
        "days_ago": 28,
        "grade": "高三",
        "exam_type": ExamType.MONTHLY,
        "scores": {"chinese": 118, "math": 108, "english": 120, "physics": 78, "chemistry": 73, "biology": 82},
    },
)


async def _ensure_user() -> tuple[User, bool]:
    """创建（或复用）演示用户，返回 `(用户, 是否新建)`。"""
    async with session_scope() as db:
        user = await db.scalar(select(User).where(User.phone == DEMO_PHONE))
        if user is not None:
            return user, False

        user = User(
            phone=DEMO_PHONE,
            password_hash=hash_password(DEMO_PASSWORD),
            nickname=DEMO_NICKNAME,
            avatar_url="",
            invite_code=gen_invite_code(),
            credits=0,
        )
        db.add(user)
        await db.flush()
        change = await add_credits(
            db,
            user_id=user.id,
            delta=DEMO_CREDITS,
            reason=CreditReason.ADMIN_ADJUST,
            idempotency_key=f"seed:{user.id}",
            ref_type="user",
            ref_id=user.id,
            remark="演示账号初始点数",
        )
        user.credits = change.balance_after
        return user, True


async def _ensure_exams(user_id: str) -> tuple[int, list[Exam]]:
    """创建（或复用）6 次考试及其答题卡 / 试卷，返回 `(新建数量, 全部考试)`。"""
    created = 0
    async with session_scope() as db:
        for spec in DEMO_EXAMS:
            name = str(spec["name"])
            exam = await db.scalar(select(Exam).where(Exam.user_id == user_id, Exam.name == name))
            if exam is not None:
                continue

            exam_date: date = date.today() - timedelta(days=int(spec["days_ago"]))  # type: ignore[arg-type]
            exam = Exam(
                user_id=user_id,
                name=name,
                exam_date=exam_date,
                grade=str(spec["grade"]),
                exam_type=spec["exam_type"],  # type: ignore[arg-type]
                total_full_score=DEMO_TOTAL_FULL_SCORE,
                note="演示数据：由 scripts/seed.py 生成",
            )
            db.add(exam)
            await db.flush()

            scores: dict[str, int] = dict(spec["scores"])  # type: ignore[arg-type]
            for code, score in scores.items():
                subject_name, full_score = SUBJECT_FULL_SCORES[code]
                db.add(
                    Paper(
                        exam_id=exam.id,
                        subject_code=code,
                        title=f"{subject_name}试卷",
                        source_type=SourceType.PHOTO,
                        object_keys=[],
                        ocr_status=TaskStatus.SUCCEEDED,
                        ocr_text=f"【演示数据】{subject_name}试卷题目占位文本。",
                        question_count=0,
                    )
                )
                db.add(
                    AnswerSheet(
                        exam_id=exam.id,
                        subject_code=code,
                        source_type=SourceType.PHOTO,
                        is_graded=True,
                        object_keys=[],
                        ocr_status=TaskStatus.SUCCEEDED,
                        score=float(score),
                        full_score=float(full_score),
                    )
                )
            created += 1

        exams = list(
            await db.scalars(
                select(Exam).where(Exam.user_id == user_id).order_by(Exam.exam_date.asc(), Exam.created_at.asc())
            )
        )
        return created, exams


async def _ensure_analysis(user_id: str, exam_id: str) -> Analysis | None:
    """为指定考试生成一条已完成的分析（已存在成功分析则跳过）。"""
    async with session_scope() as db:
        existing = await db.scalar(
            select(Analysis).where(Analysis.exam_id == exam_id, Analysis.status == TaskStatus.SUCCEEDED)
        )
        if existing is not None:
            return existing

        analysis = Analysis(
            exam_id=exam_id,
            user_id=user_id,
            status=TaskStatus.PENDING,
            credits_cost=0,  # 演示数据不扣费，也不参与退费
            model_name="",
        )
        db.add(analysis)
        await db.flush()
        analysis_id = analysis.id

    # 走真实的编排链路（组装输入 → Mock/真实 Provider → 落库 → 回写分数）
    await run_analysis(analysis_id)

    async with session_scope() as db:
        return await db.get(Analysis, analysis_id)


async def _reset(user_id: str) -> None:
    """删除演示用户及其全部数据（外键级联）。"""
    async with session_scope() as db:
        await db.execute(delete(ImportTask).where(ImportTask.user_id == user_id))
        await db.execute(delete(Analysis).where(Analysis.user_id == user_id))
        await db.execute(delete(Exam).where(Exam.user_id == user_id))
        user = await db.get(User, user_id)
        if user is not None:
            await db.delete(user)
    logger.warning("已清空演示用户 %s 的全部数据", user_id)


async def main(*, reset: bool) -> int:
    """脚本入口。"""
    await init_models()
    async with session_scope() as db:
        await ensure_dictionary(db)

    if reset:
        async with session_scope() as db:
            existing = await db.scalar(select(User.id).where(User.phone == DEMO_PHONE))
        if existing:
            await _reset(str(existing))

    user, user_created = await _ensure_user()
    user_id = user.id
    logger.info("演示用户：%s（手机号 %s，密码 %s，点数 %s）%s", user_id, DEMO_PHONE, DEMO_PASSWORD, user.credits, "（新建）" if user_created else "")

    exam_created, exams = await _ensure_exams(user_id)
    logger.info("考试数据：新建 %s 次，共 %s 次", exam_created, len(exams))

    analysis: Analysis | None = None
    if exams:
        latest = exams[-1]
        analysis = await _ensure_analysis(user_id, latest.id)
        if analysis is not None:
            logger.info(
                "分析数据：考试「%s」→ 分析 %s（%s，总分 %s / %s）",
                latest.name,
                analysis.id,
                analysis.status.value,
                analysis.total_score,
                analysis.full_score,
            )

    async with session_scope() as db:
        exam_total = int(await db.scalar(select(func.count()).select_from(Exam).where(Exam.user_id == user_id)) or 0)
        sheet_total = int(
            await db.scalar(
                select(func.count())
                .select_from(AnswerSheet)
                .join(Exam, Exam.id == AnswerSheet.exam_id)
                .where(Exam.user_id == user_id)
            )
            or 0
        )
        analysis_total = int(
            await db.scalar(select(func.count()).select_from(Analysis).where(Analysis.user_id == user_id)) or 0
        )

    logger.info("演示数据就绪：考试 %s 次、答题卡 %s 张、分析 %s 条", exam_total, sheet_total, analysis_total)
    print()
    print("=" * 68)
    print("  演示账号")
    print(f"    手机号：{DEMO_PHONE}")
    print(f"    密码：  {DEMO_PASSWORD}")
    print(f"    点数：  {DEMO_CREDITS}")
    print("  启动服务后可直接用上述账号登录；数据时间：", utcnow().strftime("%Y-%m-%d %H:%M:%S"), "UTC")
    print("=" * 68)

    await dispose_engine()
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="灌入 ToTop 演示数据")
    parser.add_argument("--reset", action="store_true", help="先删除演示用户及其全部数据，再重新灌入")
    args = parser.parse_args()

    try:
        exit_code = asyncio.run(main(reset=args.reset))
    except KeyboardInterrupt:  # pragma: no cover —— 手动中断
        exit_code = 130
    sys.exit(exit_code)
