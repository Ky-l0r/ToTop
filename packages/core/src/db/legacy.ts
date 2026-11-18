import type { SqlExecutor, SqlValue } from "./executor";

/**
 * 从 0.8.0 的备份库导入数据。
 *
 * 为什么读旧库而不是读 JSON：0.8.0 的备份包（zip）里本来就有 `db/totop.db`，
 * 那是一份完整的 SQLite 快照。另造一份导出格式等于多养一套序列化，还多一次出错的机会。
 * 于是 1.0 的导入 = 解 zip → 把旧库挂起来 → 按表拷贝。
 *
 * 兼容性靠"列名交集"实现：读出源表的真实列，只拷新旧都有的那些。
 * 这样 v1 到 v10 的旧备份都能进来，缺的列由新表的默认值补上，不需要逐版本写迁移。
 */

/** 直接同名拷贝的表。顺序按外键依赖排，父表在前。 */
const PLAIN_TABLES: readonly string[] = [
  "question",
  "mistake_record",
  "knowledge_point",
  "kpoint_edge",
  "mistake_kpoint",
  "mastery",
  "review_log",
  "hint_log",
  "exercise",
  "kpoint_method",
  "study_note",
  "forgotten_kpoint",
  "exam_session",
  "ai_trace",
  "cache_entry",
];

/** 设置项里不许跟着备份走的键：Key 本该在系统钥匙串，但旧版本可能漏在其中。 */
const SENSITIVE_SETTING = /(^|[._-])(api[_-]?key|apikey|key|token|secret|password)/i;

export interface ImportOptions {
  /** 导入时刻（UTC 毫秒），用于补时间戳缺失的行与写入导入标记。 */
  nowUtc: number;
  /** merge：保留目标库已有行；replace：先清空再灌。首启迁移用 merge 即可。 */
  mode?: "merge" | "replace";
}

export interface ImportReport {
  /** 每张表写入了多少行。 */
  counts: Record<string, number>;
  /** 源库里不存在的表（旧备份版本较老时会出现）。 */
  missingTables: string[];
  /** 被过滤掉的敏感设置项数量。 */
  filteredSettings: number;
}

export class LegacyImportError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LegacyImportError";
  }
}

/** 判断源库是不是 0.8.0 的库：有 mistake_record 就算，没有就说明选错了文件。 */
export async function looksLikeLegacyDb(source: SqlExecutor): Promise<boolean> {
  return tableExists(source, "mistake_record");
}

async function tableExists(db: SqlExecutor, table: string): Promise<boolean> {
  const rows = await db.all<{ name: string }>(
    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
    [table],
  );
  return rows.length > 0;
}

async function columnsOf(db: SqlExecutor, table: string): Promise<string[]> {
  const rows = await db.all<{ name: string }>(`PRAGMA table_info(${table})`);
  return rows.map((row) => row.name);
}

function normalize(value: unknown): SqlValue {
  if (value === undefined || value === null) return null;
  if (typeof value === "boolean") return value ? 1 : 0;
  if (typeof value === "bigint") return Number(value);
  if (typeof value === "number" || typeof value === "string") return value;
  return String(value);
}

function insertSql(table: string, columns: readonly string[]): string {
  const placeholders = columns.map(() => "?").join(", ");
  return `INSERT OR REPLACE INTO ${table} (${columns.join(", ")}) VALUES (${placeholders})`;
}

async function clearTarget(db: SqlExecutor, tables: readonly string[]): Promise<void> {
  for (const table of tables) {
    await db.run(`DELETE FROM ${table}`);
  }
}

/**
 * 把旧库的数据灌进新库。
 *
 * 必须在同一个事务里跑完：中途失败时留下一半数据，比什么都没导更糟——
 * 学生看到的会是一个"错题少了一半"的错题本，而且他不知道少了哪些。
 */
export async function importLegacyDb(
  source: SqlExecutor,
  target: SqlExecutor,
  options: ImportOptions,
): Promise<ImportReport> {
  if (!(await looksLikeLegacyDb(source))) {
    throw new LegacyImportError("这个文件不是 To Top 的备份库，里面找不到错题表");
  }

  const report: ImportReport = { counts: {}, missingTables: [], filteredSettings: 0 };

  await target.transaction(async () => {
    if (options.mode === "replace") {
      await clearTarget(target, ["material", "exam_score", ...PLAIN_TABLES]);
    }

    for (const table of PLAIN_TABLES) {
      if (!(await tableExists(source, table))) {
        report.missingTables.push(table);
        continue;
      }
      report.counts[table] = await copyPlainTable(source, target, table);
    }

    report.filteredSettings = await copySettings(source, target, options.nowUtc);
    report.counts.exam_score = await copyExamScores(source, target, options.nowUtc);
    report.counts.material = await copyLearnedMaterials(source, target, options.nowUtc);

    await target.run("INSERT OR REPLACE INTO settings (key, value, updatedAt) VALUES (?, ?, ?)", [
      "import.legacy.at",
      String(options.nowUtc),
      options.nowUtc,
    ]);
  });

  return report;
}

async function copyPlainTable(
  source: SqlExecutor,
  target: SqlExecutor,
  table: string,
): Promise<number> {
  const sourceColumns = await columnsOf(source, table);
  const targetColumns = await columnsOf(target, table);
  const shared = targetColumns.filter((name) => sourceColumns.includes(name));
  if (shared.length === 0) return 0;

  const rows = await source.all<Record<string, unknown>>(`SELECT * FROM ${table}`);
  const sql = insertSql(table, shared);
  for (const row of rows) {
    await target.run(
      sql,
      shared.map((name) => normalize(row[name])),
    );
  }
  return rows.length;
}

async function copySettings(
  source: SqlExecutor,
  target: SqlExecutor,
  nowUtc: number,
): Promise<number> {
  if (!(await tableExists(source, "settings"))) return 0;
  const rows = await source.all<{ key: string; value: string; updatedAt?: number }>(
    "SELECT * FROM settings",
  );
  let filtered = 0;
  for (const row of rows) {
    if (!row.key) continue;
    if (SENSITIVE_SETTING.test(row.key)) {
      filtered += 1;
      continue;
    }
    await target.run("INSERT OR REPLACE INTO settings (key, value, updatedAt) VALUES (?, ?, ?)", [
      row.key,
      row.value,
      normalize(row.updatedAt) ?? nowUtc,
    ]);
  }
  return filtered;
}

/**
 * 成绩单要带上"什么时候录进来的"。
 *
 * 旧库里没有这个字段，但它有考试场次的 createdAt——那正是学生录入这条成绩的时刻。
 * 用 occurredOn（考试日期）会算错：补录上周的月考时，考试日期比录入时间还早，
 * 学情页的"最近一次导入"就会挑错记录。
 */
async function copyExamScores(
  source: SqlExecutor,
  target: SqlExecutor,
  nowUtc: number,
): Promise<number> {
  if (!(await tableExists(source, "exam_score"))) return 0;
  const hasSession = await tableExists(source, "exam_session");
  const rows = hasSession
    ? await source.all<Record<string, unknown>>(
        `SELECT s.*, x.createdAt AS sessionCreatedAt
           FROM exam_score s
           LEFT JOIN exam_session x ON x.id = s.sessionId`,
      )
    : await source.all<Record<string, unknown>>("SELECT * FROM exam_score");

  const targetColumns = await columnsOf(target, "exam_score");
  const columns = targetColumns.filter((name) => name !== "importedAt");

  for (const row of rows) {
    const values = columns.map((name) => normalize(row[name]));
    const importedAt = normalize(row.sessionCreatedAt) ?? nowUtc;
    await target.run(
      insertSql("exam_score", [...columns, "importedAt"]),
      [...values, importedAt],
    );
  }
  return rows.length;
}

/**
 * 旧资料库的"方法卡"搬成新资料库的一条。
 *
 * 1.0 的资料库只收标准答案，但学生之前攒的卡片是真内容，不能因为改了产品口径就消失：
 * 统一标成 `legacy_card`，界面上与标准答案分开展示，并允许后续被标准答案覆盖。
 */
async function copyLearnedMaterials(
  source: SqlExecutor,
  target: SqlExecutor,
  nowUtc: number,
): Promise<number> {
  if (!(await tableExists(source, "learned_material"))) return 0;
  const rows = await source.all<Record<string, unknown>>("SELECT * FROM learned_material");
  for (const row of rows) {
    const title = String(row.title ?? "").trim();
    const method = String(row.method ?? "").trim();
    await target.run(
      `INSERT OR REPLACE INTO material
         (id, subject, kpointId, title, stepsJson, answer, source, origin, note, pitfallsJson, imagePath, createdAtUtc)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        normalize(row.id),
        normalize(row.subject) ?? "",
        normalize(row.kpointId),
        title || method || "未命名资料",
        normalize(row.keyStepsJson) ?? "[]",
        normalize(row.answer) ?? "",
        "legacy_card",
        "legacy",
        title ? method : "",
        normalize(row.pitfallsJson) ?? "[]",
        normalize(row.imagePath),
        normalize(row.createdAtUtc) ?? nowUtc,
      ],
    );
  }
  return rows.length;
}
