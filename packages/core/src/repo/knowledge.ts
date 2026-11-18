import type { SqlExecutor } from "../db/executor";
import {
  applyMasteryEvent,
  bandOf,
  clampScore,
  INITIAL_MASTERY,
  type MasteryBand,
  type MasteryEventKind,
} from "../mastery/algorithm";

/**
 * 知识点的取数与掌握度写回。
 *
 * 掌握度只有这一个写入口。0.8.0 有三处在改这个值（错题录入、复习打卡、练习），
 * 谁最后写谁说了算，于是同一个知识点在两页显示不同分数。
 */

export interface KpointListItem {
  id: string;
  name: string;
  subject: string;
  chapter: string;
  /** 0 到 1；没有记录时为初始值。 */
  score: number;
  mistakeCount: number;
  hasMastery: boolean;
}

export interface KpointFilter {
  subject?: string | null;
  band?: MasteryBand;
}

export async function listKpoints(
  db: SqlExecutor,
  filter: KpointFilter = {},
): Promise<KpointListItem[]> {
  const params: (string | number)[] = [];
  let where = " WHERE 1 = 1";
  if (filter.subject) {
    where += " AND k.subject = ?";
    params.push(filter.subject);
  }

  const rows = await db.all<{
    id: string;
    name: string;
    subject: string;
    chapter: string;
    score: number | null;
    mistakeCount: number;
  }>(
    `SELECT k.id AS id, k.name AS name, k.subject AS subject, k.chapter AS chapter,
            m.score AS score,
            (SELECT COUNT(*) FROM mistake_kpoint mk WHERE mk.kpointId = k.id) AS mistakeCount
       FROM knowledge_point k
       LEFT JOIN mastery m ON m.kpointId = k.id
      ${where}
      ORDER BY k.subject ASC, k.chapter ASC, k.orderIndex ASC`,
    params,
  );

  const items = rows.map((row) => ({
    id: row.id,
    name: row.name,
    subject: row.subject,
    chapter: row.chapter ?? "",
    score: row.score === null || row.score === undefined ? INITIAL_MASTERY : clampScore(row.score),
    mistakeCount: Number(row.mistakeCount ?? 0),
    hasMastery: row.score !== null && row.score !== undefined,
  }));

  if (!filter.band || filter.band === "all") return items;
  return items.filter((item) => bandOf(item.score) === filter.band);
}

export interface ChapterHeat {
  chapter: string;
  /** 该章节知识点掌握度的平均值。 */
  score: number;
  count: number;
}

/** 章节热力：按章节取平均掌握度，供列表上方那一排色块用。 */
export async function chapterHeat(
  db: SqlExecutor,
  subject: string | null,
): Promise<ChapterHeat[]> {
  const params: string[] = [];
  let where = "";
  if (subject) {
    where = " WHERE k.subject = ?";
    params.push(subject);
  }

  const rows = await db.all<{ chapter: string; score: number | null; total: number }>(
    `SELECT k.chapter AS chapter, AVG(COALESCE(m.score, ?)) AS score, COUNT(*) AS total
       FROM knowledge_point k
       LEFT JOIN mastery m ON m.kpointId = k.id
      ${where}
      GROUP BY k.chapter
      ORDER BY k.chapter ASC`,
    [INITIAL_MASTERY, ...params],
  );

  return rows.map((row) => ({
    chapter: row.chapter ?? "未分章",
    score: clampScore(Number(row.score ?? INITIAL_MASTERY)),
    count: Number(row.total ?? 0),
  }));
}

export interface MasterySnapshot {
  kpointId: string;
  name: string;
  subject: string;
  chapter: string;
  score: number;
  updatedAt: number;
  history: Array<{ kind: MasteryEventKind; score: number; at: number }>;
  mistakes: Array<{
    mistakeId: string;
    stem: string;
    errorType: string;
    createdAt: number;
  }>;
}

export async function kpointDetail(
  db: SqlExecutor,
  kpointId: string,
): Promise<MasterySnapshot | null> {
  const rows = await db.all<{
    id: string;
    name: string;
    subject: string;
    chapter: string;
    score: number | null;
    historyJson: string | null;
    updatedAt: number | null;
  }>(
    `SELECT k.id AS id, k.name AS name, k.subject AS subject, k.chapter AS chapter,
            m.score AS score, m.historyJson AS historyJson, m.updatedAt AS updatedAt
       FROM knowledge_point k
       LEFT JOIN mastery m ON m.kpointId = k.id
      WHERE k.id = ?`,
    [kpointId],
  );
  const row = rows[0];
  if (!row) return null;

  const mistakes = await db.all<{
    mistakeId: string;
    stem: string;
    errorType: string;
    createdAt: number;
  }>(
    `SELECT m.id AS mistakeId, q.stemText AS stem, m.errorType AS errorType, m.createdAt AS createdAt
       FROM mistake_kpoint mk
       JOIN mistake_record m ON m.id = mk.mistakeId
       JOIN question q ON q.id = m.questionId
      WHERE mk.kpointId = ?
      ORDER BY m.createdAt DESC
      LIMIT 50`,
    [kpointId],
  );

  return {
    kpointId: row.id,
    name: row.name,
    subject: row.subject,
    chapter: row.chapter ?? "",
    score: row.score === null || row.score === undefined ? INITIAL_MASTERY : clampScore(row.score),
    updatedAt: Number(row.updatedAt ?? 0),
    history: parseHistory(row.historyJson),
    mistakes,
  };
}

/**
 * 写回一次掌握度事件。
 *
 * 历史只留最近 20 条：它是"这个数为什么变了"的依据，不是数据资产。
 * 留太多会让 historyJson 变成一个越滚越大的文本列。
 */
export async function applyMastery(
  db: SqlExecutor,
  kpointId: string,
  kind: MasteryEventKind,
  nowUtc: number,
): Promise<number> {
  const rows = await db.all<{ score: number | null; historyJson: string | null }>(
    "SELECT score, historyJson FROM mastery WHERE kpointId = ?",
    [kpointId],
  );
  const current =
    rows[0]?.score === null || rows[0]?.score === undefined
      ? INITIAL_MASTERY
      : clampScore(rows[0].score);
  const next = applyMasteryEvent(current, kind);

  const history = [
    ...parseHistory(rows[0]?.historyJson ?? null),
    { kind, score: next, at: nowUtc },
  ].slice(-20);

  await db.run(
    `INSERT OR REPLACE INTO mastery (kpointId, score, historyJson, updatedAt)
     VALUES (?, ?, ?, ?)`,
    [kpointId, next, JSON.stringify(history), nowUtc],
  );
  return next;
}

/**
 * 学生自己说"这题我会了"。
 *
 * 1.0 不在错题上加状态（不熟/存疑/理解全删了），但掌握度需要一个上行入口——
 * 否则它只会因为错题一路下跌，学生看到的永远是"我越来越差"。
 * 这里按该错题挂的知识点各记一次"做对"，不区分权重：学生已经自己判断过了。
 */
export async function markMistakeMastered(
  db: SqlExecutor,
  mistakeId: string,
  nowUtc: number,
): Promise<number> {
  const rows = await db.all<{ kpointId: string }>(
    "SELECT kpointId FROM mistake_kpoint WHERE mistakeId = ?",
    [mistakeId],
  );
  for (const row of rows) {
    await applyMastery(db, row.kpointId, "correct", nowUtc);
  }
  return rows.length;
}

function parseHistory(
  raw: string | null,
): Array<{ kind: MasteryEventKind; score: number; at: number }> {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (item): item is { kind: MasteryEventKind; score: number; at: number } =>
        typeof item === "object" &&
        item !== null &&
        typeof (item as { score?: unknown }).score === "number",
    );
  } catch {
    return [];
  }
}
