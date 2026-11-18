import type { SqlExecutor } from "../db/executor";

/**
 * 首页要的三个数字与"最近不熟的知识点"。
 *
 * 口径统一在这里算：首页、易错题页、学情页必须用同一个函数取数。
 * 0.8.0 的三个页面各算一遍，结果对不上，学生截图来问"到底哪个是对的"。
 */

/** 判定"已掌握"的分数线。掌握度算法在 T10 落地，落地后这个常量跟着算法走。 */
export const MASTERY_MASTERED_THRESHOLD = 0.6;

export interface LatestExam {
  subject: string;
  score: number;
  fullScore: number;
  /** 得分率，0 到 1。 */
  rate: number;
  occurredOn: number;
}

export interface HomeStats {
  mistakesLast7Days: number;
  latestExam: LatestExam | null;
  masteredKpoints: number;
}

const SEVEN_DAYS = 7 * 86_400_000;

export async function homeStats(db: SqlExecutor, nowUtc: number): Promise<HomeStats> {
  const mistakeRows = await db.all<{ total: number }>(
    "SELECT COUNT(*) AS total FROM mistake_record WHERE createdAt >= ?",
    [nowUtc - SEVEN_DAYS],
  );

  const masteredRows = await db.all<{ total: number }>(
    "SELECT COUNT(*) AS total FROM mastery WHERE score >= ?",
    [MASTERY_MASTERED_THRESHOLD],
  );

  // 最近一次"录入"的成绩：按 importedAt 排，不是按考试日期。
  // 补录上周月考时，考试日期比录入时间还早，按日期排会挑错那条。
  const examRows = await db.all<{
    subject: string;
    score: number;
    fullScore: number;
    occurredOn: number;
  }>(
    `SELECT s.subject AS subject, s.score AS score, s.fullScore AS fullScore,
            x.occurredOn AS occurredOn
       FROM exam_score s
       JOIN exam_session x ON x.id = s.sessionId
      ORDER BY s.importedAt DESC, x.occurredOn DESC
      LIMIT 1`,
  );

  const exam = examRows[0];
  return {
    mistakesLast7Days: Number(mistakeRows[0]?.total ?? 0),
    masteredKpoints: Number(masteredRows[0]?.total ?? 0),
    latestExam: exam
      ? {
          subject: exam.subject,
          score: exam.score,
          fullScore: exam.fullScore,
          rate: exam.fullScore > 0 ? exam.score / exam.fullScore : 0,
          occurredOn: exam.occurredOn,
        }
      : null,
  };
}

export interface WeakKpoint {
  id: string;
  name: string;
  subject: string;
  /** 挂在这个知识点上的错题数。 */
  mistakeCount: number;
  /** 陪伴页问过的次数。 */
  askCount: number;
  /** 排序权重：错题权重更高，因为那是实打实做错的。 */
  weight: number;
}

/**
 * 最近不熟的知识点。
 *
 * 合并两个来源：「写作业时常问的」（forgotten_kpoint）与「还需要多练的」（mistake_kpoint）。
 * 权重上错题 1 分、提问 0.5 分：问过一次可能是好奇，做错一次是真的没会。
 * 两者都没有的知识点不进列表——不熟要由行为证明，不能凭猜。
 */
export async function weakKpoints(db: SqlExecutor, limit = 5): Promise<WeakKpoint[]> {
  const rows = await db.all<{
    id: string;
    name: string;
    subject: string;
    mistakeCount: number;
    askCount: number;
  }>(
    // 套一层子查询再过滤：SQLite 不保证能在外层 WHERE 里引用同层 SELECT 的别名，
    // 写成子查询是唯一在两端都稳的写法。排序也放在外层，用算好的计数。
    `SELECT id, name, subject, mistakeCount, askCount
       FROM (
         SELECT k.id AS id,
                k.name AS name,
                k.subject AS subject,
                (SELECT COUNT(*) FROM mistake_kpoint mk WHERE mk.kpointId = k.id) AS mistakeCount,
                COALESCE((SELECT f.askCount FROM forgotten_kpoint f WHERE f.kpointId = k.id), 0) AS askCount
           FROM knowledge_point k
       )
      WHERE mistakeCount > 0 OR askCount > 0
      ORDER BY (mistakeCount + askCount * 0.5) DESC, name ASC
      LIMIT ?`,
    [limit],
  );

  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    subject: row.subject,
    mistakeCount: Number(row.mistakeCount ?? 0),
    askCount: Number(row.askCount ?? 0),
    weight: Number(row.mistakeCount ?? 0) + Number(row.askCount ?? 0) * 0.5,
  }));
}
