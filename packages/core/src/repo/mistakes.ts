import type { SqlExecutor } from "../db/executor";
import type {
  ErrorReportSpec,
  KpointMatchSpec,
  QuestionSpec,
  SolutionStep,
  StandardSolutionSpec,
  StudentStepSpec,
} from "../solve/contracts";
import { createId } from "../util/id";
import { applyMastery } from "./knowledge";

/**
 * 错题的写入与读取。
 *
 * 写：一次解答落三张表——question（题目）、mistake_record（这次错在哪）、
 * mistake_kpoint（挂在哪几个知识点上）。
 *
 * 读：只按四个条件筛（科目 / 知识点 / 错因 / 时间），**不读 status**。
 * 0.8.0 的四态（不会/错误/存疑/理解）在 1.0 不再展示：它要求学生每次自己判断
 * "我到底算不算懂了"，而学生的判断往往跟着心情走。掌握程度统一放到知识点页看。
 */

export interface SaveSolvedInput {
  spec: QuestionSpec;
  solution: StandardSolutionSpec;
  report: ErrorReportSpec | null;
  kpoints: KpointMatchSpec | null;
  mode: "DIRECT" | "GUIDED";
  nowUtc: number;
  /** 题图路径与指纹，没有图时留空。 */
  imagePath?: string | null;
  imageSha256?: string | null;
  random?: () => number;
}

export interface SavedIds {
  questionId: string;
  mistakeId: string;
}

/**
 * 落库一次解答。
 *
 * `status` 写空串而不是 'wrong'：新录入不再产生状态值（M-9），
 * 但列上有 NOT NULL，空串是唯一既能满足约束又不表达状态的值。
 * 读取端不认这个字段，历史数据里的旧值原样留着（M-8）。
 */
export async function saveSolvedQuestion(
  db: SqlExecutor,
  input: SaveSolvedInput,
): Promise<SavedIds> {
  const random = input.random ?? Math.random;
  const questionId = createId("q", input.nowUtc, random);
  const mistakeId = createId("m", input.nowUtc, random);
  const firstError = input.report?.firstError ?? null;

  await db.transaction(async () => {
    await db.run(
      `INSERT INTO question
         (id, subject, grade, stemText, knownJson, ask, optionsJson, hasFigure,
          imagePath, imageSha256, sourceType, createdAt, selfMasteredAt, imagePathsJson,
          questionType, choiceType)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        questionId,
        input.spec.subject,
        input.spec.grade,
        input.spec.stem,
        JSON.stringify(input.spec.known),
        input.spec.ask,
        JSON.stringify(input.spec.options),
        input.spec.hasFigure ? 1 : 0,
        input.imagePath ?? null,
        input.imageSha256 ?? null,
        input.imagePath ? "photo" : "text",
        input.nowUtc,
        null,
        JSON.stringify(input.imagePath ? [input.imagePath] : []),
        input.spec.questionType,
        input.spec.choiceType,
      ],
    );

    await db.run(
      `INSERT INTO mistake_record
         (id, questionId, studentStepsJson, firstErrorStepIdx, errorType, errorExplain,
          correctPathJson, finalAnswer, mode, createdAt, recurrenceIndex, status, source)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        mistakeId,
        questionId,
        JSON.stringify(input.report?.studentSteps ?? []),
        firstError ? firstError.stepIdx : null,
        firstError ? firstError.type : "",
        firstError ? firstError.explain : "",
        JSON.stringify(input.solution.steps),
        input.solution.finalAnswer,
        input.mode,
        input.nowUtc,
        0,
        "",
        "solve",
      ],
    );

    for (const kpoint of input.kpoints?.kpoints ?? []) {
      await db.run(
        "INSERT OR REPLACE INTO mistake_kpoint (mistakeId, kpointId, confidence) VALUES (?, ?, ?)",
        [mistakeId, kpoint.id, kpoint.confidence],
      );
      // 错题是掌握度的输入之一。写在这里而不是页面里，
      // 是为了让"任何路径写进来的错题都会影响掌握度"——包括将来的旧数据导入。
      await applyMastery(db, kpoint.id, "mistake", input.nowUtc);
    }
  });

  return { questionId, mistakeId };
}

/** 四个筛选条件。全部为空时就是"全部"。 */
export interface MistakeFilter {
  subject?: string | null;
  kpointId?: string | null;
  errorType?: string | null;
  /** 只列该时间之后（含）的错题。 */
  sinceUtc?: number | null;
}

export interface MistakeListItem {
  mistakeId: string;
  questionId: string;
  subject: string;
  questionType: string;
  choiceType: string;
  stem: string;
  errorType: string;
  errorExplain: string;
  finalAnswer: string;
  createdAt: number;
  recurrenceIndex: number;
  kpointName: string | null;
  imagePath: string | null;
}

const LIST_SQL = `
  SELECT m.id AS mistakeId,
         m.questionId AS questionId,
         q.subject AS subject,
         q.questionType AS questionType,
         q.choiceType AS choiceType,
         q.stemText AS stem,
         m.errorType AS errorType,
         m.errorExplain AS errorExplain,
         m.finalAnswer AS finalAnswer,
         m.createdAt AS createdAt,
         m.recurrenceIndex AS recurrenceIndex,
         (SELECT k.name
            FROM mistake_kpoint mk
            JOIN knowledge_point k ON k.id = mk.kpointId
           WHERE mk.mistakeId = m.id
           ORDER BY mk.confidence DESC
           LIMIT 1) AS kpointName,
         q.imagePath AS imagePath
    FROM mistake_record m
    JOIN question q ON q.id = m.questionId
`;

function buildWhere(filter: MistakeFilter): { where: string; params: (string | number)[] } {
  const conditions: string[] = [];
  const params: (string | number)[] = [];

  if (filter.subject) {
    conditions.push("q.subject = ?");
    params.push(filter.subject);
  }
  if (filter.kpointId) {
    conditions.push(
      "EXISTS (SELECT 1 FROM mistake_kpoint mk2 WHERE mk2.mistakeId = m.id AND mk2.kpointId = ?)",
    );
    params.push(filter.kpointId);
  }
  if (filter.errorType) {
    conditions.push("m.errorType = ?");
    params.push(filter.errorType);
  }
  if (typeof filter.sinceUtc === "number") {
    conditions.push("m.createdAt >= ?");
    params.push(filter.sinceUtc);
  }

  return { where: conditions.length > 0 ? ` WHERE ${conditions.join(" AND ")}` : "", params };
}

export async function listMistakes(
  db: SqlExecutor,
  filter: MistakeFilter = {},
  limit = 100,
): Promise<MistakeListItem[]> {
  const { where, params } = buildWhere(filter);
  const sql = `${LIST_SQL}${where} ORDER BY m.createdAt DESC LIMIT ?`;
  return await db.all<MistakeListItem>(sql, [...params, limit]);
}

export async function countMistakes(db: SqlExecutor, filter: MistakeFilter = {}): Promise<number> {
  const { where, params } = buildWhere(filter);
  const rows = await db.all<{ total: number }>(
    `SELECT COUNT(*) AS total
       FROM mistake_record m
       JOIN question q ON q.id = m.questionId
     ${where}`,
    params,
  );
  return Number(rows[0]?.total ?? 0);
}

/** 知识点筛选的候选项：只列出"确实挂过错题"的知识点，按挂载数排。 */
export interface KpointOption {
  id: string;
  name: string;
  count: number;
}

export async function listMistakeKpoints(db: SqlExecutor): Promise<KpointOption[]> {
  return await db.all<KpointOption>(
    `SELECT k.id AS id, k.name AS name, COUNT(*) AS count
       FROM mistake_kpoint mk
       JOIN knowledge_point k ON k.id = mk.kpointId
      GROUP BY k.id, k.name
      ORDER BY count DESC, k.name ASC`,
  );
}

export interface MistakeDetail extends MistakeListItem {
  ask: string;
  known: string[];
  options: string[];
  method: string;
  check: string;
  /** 标准解答的步骤。第一次解答的完整过程也在这里。 */
  steps: SolutionStep[];
  /** 学生当时写的步骤（可能为空）。 */
  studentSteps: StudentStepSpec[];
  /** 改写前的旧解答，没有改写时为 null。 */
  revision: { steps: SolutionStep[]; finalAnswer: string; reason: string; createdAt: number } | null;
}

export async function getMistake(db: SqlExecutor, mistakeId: string): Promise<MistakeDetail | null> {
  const rows = await db.all<Record<string, unknown>>(
    `${LIST_SQL} WHERE m.id = ?`,
    [mistakeId],
  );
  const row = rows[0];
  if (!row) return null;

  const extra = await db.all<{
    ask: string;
    knownJson: string;
    optionsJson: string;
    correctPathJson: string;
    studentStepsJson: string;
  }>(
    `SELECT q.ask AS ask, q.knownJson AS knownJson, q.optionsJson AS optionsJson,
            m.correctPathJson AS correctPathJson, m.studentStepsJson AS studentStepsJson
       FROM mistake_record m JOIN question q ON q.id = m.questionId
      WHERE m.id = ?`,
    [mistakeId],
  );
  const detail = extra[0];

  const revisionRows = await db.all<{
    stepsJson: string;
    finalAnswer: string;
    reason: string;
    createdAt: number;
  }>(
    "SELECT stepsJson, finalAnswer, reason, createdAt FROM solution_revision WHERE mistakeId = ?",
    [mistakeId],
  );
  const revision = revisionRows[0];

  return {
    mistakeId: String(row["mistakeId"] ?? ""),
    questionId: String(row["questionId"] ?? ""),
    subject: String(row["subject"] ?? ""),
    questionType: String(row["questionType"] ?? ""),
    choiceType: String(row["choiceType"] ?? ""),
    stem: String(row["stem"] ?? ""),
    errorType: String(row["errorType"] ?? ""),
    errorExplain: String(row["errorExplain"] ?? ""),
    finalAnswer: String(row["finalAnswer"] ?? ""),
    createdAt: Number(row["createdAt"] ?? 0),
    recurrenceIndex: Number(row["recurrenceIndex"] ?? 0),
    kpointName: row["kpointName"] === null || row["kpointName"] === undefined
      ? null
      : String(row["kpointName"]),
    imagePath: row["imagePath"] === null || row["imagePath"] === undefined
      ? null
      : String(row["imagePath"]),
    ask: detail?.ask ?? "",
    known: parseArray<string>(detail?.knownJson),
    options: parseArray<string>(detail?.optionsJson),
    method: "",
    check: "",
    steps: parseArray<SolutionStep>(detail?.correctPathJson),
    studentSteps: parseArray<StudentStepSpec>(detail?.studentStepsJson),
    revision: revision
      ? {
          steps: parseArray<SolutionStep>(revision.stepsJson),
          finalAnswer: revision.finalAnswer,
          reason: revision.reason,
          createdAt: revision.createdAt,
        }
      : null,
  };
}

function parseArray<T>(raw: string | undefined): T[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? (parsed as T[]) : [];
  } catch {
    return [];
  }
}

/** 相对时间：列表上只显示"3 天前"这种，绝对值放详情页。 */
export function relativeTime(from: number, now: number): string {
  const diff = Math.max(0, now - from);
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;

  if (diff < minute) return "刚刚";
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`;
  if (diff < 30 * day) return `${Math.floor(diff / day)} 天前`;
  return `${Math.floor(diff / (30 * day))} 个月前`;
}
