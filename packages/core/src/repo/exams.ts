import type { SqlExecutor } from "../db/executor";
import { createId } from "../util/id";

/**
 * 学情的取数与录入。
 *
 * 两条口径必须由这里统一：
 * 1. 单科只看**该科最近一次录入**的那条，不混别的科目；
 * 2. 选"全部科目"时只统计**各科都录过**的场次——否则一场只录了数学的考试
 *    会在总分趋势里拉出一个假低谷。
 *
 * "最近一次"按 `importedAt` 排，不是考试日期。补录上周月考时日期会倒挂。
 */

export interface ExamScoreRow {
  subject: string;
  score: number;
  fullScore: number;
}

export interface ExamSessionRow {
  id: string;
  name: string;
  occurredOn: number;
  importedAt: number;
  scores: ExamScoreRow[];
  /** 该场次的平均得分率，0 到 1。全部科目视图用它画趋势。 */
  rate: number;
}

export interface SaveExamInput {
  /** 例如「十月月考」，可以为空。 */
  name: string;
  occurredOn: number;
  kind: string;
  scores: ExamScoreRow[];
  nowUtc: number;
  random?: () => number;
}

export async function saveExam(db: SqlExecutor, input: SaveExamInput): Promise<string> {
  const sessionId = createId("e", input.nowUtc, input.random ?? Math.random);

  await db.transaction(async () => {
    await db.run(
      `INSERT INTO exam_session (id, occurredOn, kind, name, createdAt, updatedAt)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [sessionId, input.occurredOn, input.kind, input.name, input.nowUtc, input.nowUtc],
    );

    for (const row of input.scores) {
      // 成绩行的 ID 由「场次 + 科目」决定，不用随机：同一场考试一次录三科，
      // 随机段有限（4 位 36 进制），同毫秒内撞车不是理论问题——第一版就撞了。
      await db.run(
        `INSERT OR REPLACE INTO exam_score (id, sessionId, subject, score, fullScore, importedAt)
         VALUES (?, ?, ?, ?, ?, ?)`,
        [
          `s_${sessionId}_${row.subject}`,
          sessionId,
          row.subject,
          row.score,
          row.fullScore > 0 ? row.fullScore : 100,
          input.nowUtc,
        ],
      );
    }
  });

  return sessionId;
}

/** 库里出现过成绩的科目，按字母序，用于顶部科目选择。 */
export async function scoredSubjects(db: SqlExecutor): Promise<string[]> {
  const rows = await db.all<{ subject: string }>(
    "SELECT DISTINCT subject FROM exam_score ORDER BY subject",
  );
  return rows.map((row) => row.subject);
}

/**
 * 一次取回若干场次（含各科分数），按导入时间倒序。
 *
 * `subject` 为空时只留"各科都录过"的场次：先把库里出现过的科目数算出来，
 * 再要求每场次的科目数不小于它。
 */
export async function listExams(
  db: SqlExecutor,
  subject: string | null,
  limit = 12,
): Promise<ExamSessionRow[]> {
  const sessions = subject
    ? await db.all<{ id: string; name: string; occurredOn: number; importedAt: number }>(
        `SELECT DISTINCT x.id AS id, x.name AS name, x.occurredOn AS occurredOn,
                s.importedAt AS importedAt
           FROM exam_session x
           JOIN exam_score s ON s.sessionId = x.id
          WHERE s.subject = ?
          ORDER BY s.importedAt DESC, x.occurredOn DESC
          LIMIT ?`,
        [subject, limit],
      )
    : await db.all<{ id: string; name: string; occurredOn: number; importedAt: number }>(
        `SELECT x.id AS id, x.name AS name, x.occurredOn AS occurredOn,
                MAX(s.importedAt) AS importedAt
           FROM exam_session x
           JOIN exam_score s ON s.sessionId = x.id
          GROUP BY x.id
         HAVING COUNT(DISTINCT s.subject) >= (SELECT COUNT(DISTINCT subject) FROM exam_score)
          ORDER BY importedAt DESC
          LIMIT ?`,
        [limit],
      );

  const result: ExamSessionRow[] = [];
  for (const session of sessions) {
    // 单科视图只带该科的分数：详情区不该出现别的科目（T-2）。
    const scores = subject
      ? await db.all<ExamScoreRow>(
          "SELECT subject, score, fullScore FROM exam_score WHERE sessionId = ? AND subject = ?",
          [session.id, subject],
        )
      : await db.all<ExamScoreRow>(
          "SELECT subject, score, fullScore FROM exam_score WHERE sessionId = ? ORDER BY subject",
          [session.id],
        );
    result.push({
      id: session.id,
      name: session.name ?? "",
      occurredOn: session.occurredOn,
      importedAt: session.importedAt,
      scores,
      rate: averageRate(scores),
    });
  }
  return result;
}

/** 最近一次录入（单科或全部科目）。 */
export async function latestExam(
  db: SqlExecutor,
  subject: string | null,
): Promise<ExamSessionRow | null> {
  const list = await listExams(db, subject, 1);
  return list[0] ?? null;
}

/** 趋势：按考试日期升序，供折线图从左到右画。 */
export async function examTrend(
  db: SqlExecutor,
  subject: string | null,
  limit = 12,
): Promise<Array<{ occurredOn: number; rate: number }>> {
  const list = await listExams(db, subject, limit);
  return list
    .slice()
    .reverse()
    .map((session) => ({
      occurredOn: session.occurredOn,
      rate: subject ? singleRate(session, subject) : session.rate,
    }));
}

function singleRate(session: ExamSessionRow, subject: string): number {
  const row = session.scores.find((item) => item.subject === subject);
  if (!row || row.fullScore <= 0) return 0;
  return row.score / row.fullScore;
}

function averageRate(scores: readonly ExamScoreRow[]): number {
  const rates = scores.filter((row) => row.fullScore > 0).map((row) => row.score / row.fullScore);
  if (rates.length === 0) return 0;
  return rates.reduce((sum, value) => sum + value, 0) / rates.length;
}
