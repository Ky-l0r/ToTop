import type { SqlExecutor } from "../db/executor";
import type { SolutionStep } from "../solve/contracts";

/**
 * 改写落库与回退。
 *
 * 只碰两个字段：`mistake_record.correctPathJson` 与 `finalAnswer`。
 * **不动**学生步骤、错因、知识点归属、复发次数、掌握度（S-56）：
 * 这是同一道题的结论修正，不是重新做错一次；动了会让掌握度凭空多算一轮。
 */

export interface ApplyRewriteArgs {
  mistakeId: string;
  steps: SolutionStep[];
  finalAnswer: string;
  /** `standard_answer`（导入标准答案）或 `dialog`（对话订正）。 */
  reason: string;
  nowUtc: number;
}

export type ApplyRewriteResult =
  | { ok: true }
  | { ok: false; reason: "not_found" | "unchanged" };

export async function applyRewrite(
  db: SqlExecutor,
  args: ApplyRewriteArgs,
): Promise<ApplyRewriteResult> {
  const rows = await db.all<{ correctPathJson: string; finalAnswer: string }>(
    "SELECT correctPathJson, finalAnswer FROM mistake_record WHERE id = ?",
    [args.mistakeId],
  );
  const current = rows[0];
  // 题未入库时给可执行的回执，不假装已写入（S-58）
  if (!current) return { ok: false, reason: "not_found" };

  const nextSteps = JSON.stringify(args.steps);
  if (nextSteps === current.correctPathJson && args.finalAnswer === current.finalAnswer) {
    return { ok: false, reason: "unchanged" };
  }

  await db.transaction(async () => {
    // 只留"上一版"：一道题一条旧版，再改一次就把更早的那版替换掉。
    // 留完整历史会让"回退"变成"回退到哪一版"的选择题，学生并不需要。
    await db.run(
      `INSERT OR REPLACE INTO solution_revision (mistakeId, stepsJson, finalAnswer, reason, createdAt)
       VALUES (?, ?, ?, ?, ?)`,
      [args.mistakeId, current.correctPathJson, current.finalAnswer, args.reason, args.nowUtc],
    );
    await db.run(
      "UPDATE mistake_record SET correctPathJson = ?, finalAnswer = ? WHERE id = ?",
      [nextSteps, args.finalAnswer, args.mistakeId],
    );
  });

  return { ok: true };
}

export type RevertResult = { ok: true } | { ok: false; reason: "not_found" | "no_revision" };

/**
 * 回退到上一版。
 *
 * 错题本与解答卡读的是同一行数据，所以一次回退两边同时回到旧版——
 * 不存在"只回一半"的情况。
 */
export async function revertRewrite(db: SqlExecutor, mistakeId: string): Promise<RevertResult> {
  const rows = await db.all<{ stepsJson: string; finalAnswer: string }>(
    "SELECT stepsJson, finalAnswer FROM solution_revision WHERE mistakeId = ?",
    [mistakeId],
  );
  const revision = rows[0];
  if (!revision) return { ok: false, reason: "no_revision" };

  const exists = await db.all<{ id: string }>(
    "SELECT id FROM mistake_record WHERE id = ?",
    [mistakeId],
  );
  if (exists.length === 0) return { ok: false, reason: "not_found" };

  await db.transaction(async () => {
    await db.run(
      "UPDATE mistake_record SET correctPathJson = ?, finalAnswer = ? WHERE id = ?",
      [revision.stepsJson, revision.finalAnswer, mistakeId],
    );
    await db.run("DELETE FROM solution_revision WHERE mistakeId = ?", [mistakeId]);
  });

  return { ok: true };
}

export interface RevisionInfo {
  steps: SolutionStep[];
  finalAnswer: string;
  reason: string;
  createdAt: number;
}

export async function getRevision(
  db: SqlExecutor,
  mistakeId: string,
): Promise<RevisionInfo | null> {
  const rows = await db.all<{
    stepsJson: string;
    finalAnswer: string;
    reason: string;
    createdAt: number;
  }>(
    "SELECT stepsJson, finalAnswer, reason, createdAt FROM solution_revision WHERE mistakeId = ?",
    [mistakeId],
  );
  const row = rows[0];
  if (!row) return null;

  let steps: SolutionStep[] = [];
  try {
    const parsed = JSON.parse(row.stepsJson) as unknown;
    if (Array.isArray(parsed)) steps = parsed as SolutionStep[];
  } catch {
    steps = [];
  }

  return {
    steps,
    finalAnswer: row.finalAnswer,
    reason: row.reason,
    createdAt: Number(row.createdAt ?? 0),
  };
}
