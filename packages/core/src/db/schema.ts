/**
 * 1.0 的库结构与建表语句。
 *
 * 三条约定：
 * 1. **列名沿用 0.8.0 的 camelCase**，导入旧备份时是一一对应的拷贝，不做字段改名，
 *    改名的代价是每次迁移都要写一份映射表，而映射表最容易写错。
 * 2. **删表要留理由**。这里没有 outbox / daily_stats / report：前者是无后端时代的
 *    离线写队列，后两者是日汇总与周报，1.0 都不做，留着只会有人往里写。
 * 3. **加列只加在末尾**，方便旧备份按列名交集拷贝（见 legacy.ts）。
 */
export const SCHEMA_VERSION = 2;

export const SCHEMA_STATEMENTS: readonly string[] = [
  // ---------- 题目与错题 ----------
  `CREATE TABLE IF NOT EXISTS question (
    id TEXT PRIMARY KEY NOT NULL,
    subject TEXT NOT NULL,
    grade TEXT NOT NULL DEFAULT '',
    stemText TEXT NOT NULL DEFAULT '',
    knownJson TEXT NOT NULL DEFAULT '[]',
    ask TEXT NOT NULL DEFAULT '',
    optionsJson TEXT NOT NULL DEFAULT '[]',
    hasFigure INTEGER NOT NULL DEFAULT 0,
    imagePath TEXT,
    imageSha256 TEXT,
    sourceType TEXT NOT NULL DEFAULT 'photo',
    createdAt INTEGER NOT NULL,
    selfMasteredAt INTEGER,
    imagePathsJson TEXT NOT NULL DEFAULT '[]',
    questionType TEXT NOT NULL DEFAULT '',
    choiceType TEXT NOT NULL DEFAULT ''
  )`,

  `CREATE TABLE IF NOT EXISTS mistake_record (
    id TEXT PRIMARY KEY NOT NULL,
    questionId TEXT NOT NULL,
    studentStepsJson TEXT NOT NULL DEFAULT '[]',
    firstErrorStepIdx INTEGER,
    errorType TEXT NOT NULL DEFAULT '',
    errorExplain TEXT NOT NULL DEFAULT '',
    correctPathJson TEXT NOT NULL DEFAULT '[]',
    finalAnswer TEXT NOT NULL DEFAULT '',
    mode TEXT NOT NULL DEFAULT 'GUIDED',
    createdAt INTEGER NOT NULL,
    recurrenceIndex INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'wrong',
    source TEXT NOT NULL DEFAULT 'solve',
    FOREIGN KEY (questionId) REFERENCES question (id) ON DELETE CASCADE
  )`,

  // 改写前的旧解答，一道题只留一版（回退用）。写满再回退两次的情况不存在，
  // 留历史栈会让"当前版本是哪一版"变成需要解释的问题。
  `CREATE TABLE IF NOT EXISTS solution_revision (
    mistakeId TEXT PRIMARY KEY NOT NULL,
    stepsJson TEXT NOT NULL DEFAULT '[]',
    finalAnswer TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT 'standard_answer',
    createdAt INTEGER NOT NULL,
    FOREIGN KEY (mistakeId) REFERENCES mistake_record (id) ON DELETE CASCADE
  )`,

  // ---------- 知识点 ----------
  `CREATE TABLE IF NOT EXISTS knowledge_point (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    subject TEXT NOT NULL,
    parentId TEXT,
    chapter TEXT NOT NULL DEFAULT '',
    orderIndex INTEGER NOT NULL DEFAULT 0,
    definition TEXT NOT NULL DEFAULT '',
    commonApproach TEXT NOT NULL DEFAULT '',
    pitfallJson TEXT NOT NULL DEFAULT '[]'
  )`,

  `CREATE TABLE IF NOT EXISTS kpoint_edge (
    fromId TEXT NOT NULL,
    toId TEXT NOT NULL,
    type TEXT NOT NULL,
    weight REAL NOT NULL DEFAULT 1.0,
    PRIMARY KEY (fromId, toId, type)
  )`,

  `CREATE TABLE IF NOT EXISTS mistake_kpoint (
    mistakeId TEXT NOT NULL,
    kpointId TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 1.0,
    PRIMARY KEY (mistakeId, kpointId),
    FOREIGN KEY (mistakeId) REFERENCES mistake_record (id) ON DELETE CASCADE
  )`,

  `CREATE TABLE IF NOT EXISTS mastery (
    kpointId TEXT PRIMARY KEY NOT NULL,
    score REAL NOT NULL DEFAULT 0,
    historyJson TEXT NOT NULL DEFAULT '[]',
    updatedAt INTEGER NOT NULL
  )`,

  // ---------- 历史留痕（复习打卡已下线，但历史自评仍参与掌握度） ----------
  `CREATE TABLE IF NOT EXISTS review_log (
    id TEXT PRIMARY KEY NOT NULL,
    mistakeId TEXT NOT NULL,
    reviewedAt INTEGER NOT NULL,
    selfResult TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'self',
    FOREIGN KEY (mistakeId) REFERENCES mistake_record (id) ON DELETE CASCADE
  )`,

  `CREATE TABLE IF NOT EXISTS hint_log (
    id TEXT PRIMARY KEY NOT NULL,
    mistakeId TEXT NOT NULL,
    level INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    FOREIGN KEY (mistakeId) REFERENCES mistake_record (id) ON DELETE CASCADE
  )`,

  // ---------- 练习 ----------
  `CREATE TABLE IF NOT EXISTS exercise (
    id TEXT PRIMARY KEY NOT NULL,
    kpointId TEXT NOT NULL,
    stem TEXT NOT NULL DEFAULT '',
    answer TEXT NOT NULL DEFAULT '',
    difficulty TEXT NOT NULL DEFAULT '',
    verified INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL
  )`,

  // ---------- 资料库：只放标准答案（0.8.0 的 learned_material 迁入 source='legacy_card'） ----------
  // pitfallsJson 只为导入保真而留，界面不读它：老卡片里的易错点是学生自己攒的，删掉就找不回来了。
  `CREATE TABLE IF NOT EXISTS material (
    id TEXT PRIMARY KEY NOT NULL,
    subject TEXT NOT NULL DEFAULT '',
    kpointId TEXT,
    title TEXT NOT NULL DEFAULT '',
    stem TEXT NOT NULL DEFAULT '',
    stepsJson TEXT NOT NULL DEFAULT '[]',
    answer TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'standard_answer',
    origin TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT '',
    explanation TEXT NOT NULL DEFAULT '',
    pitfallsJson TEXT NOT NULL DEFAULT '[]',
    imagePath TEXT,
    createdAtUtc INTEGER NOT NULL
  )`,

  // ---------- 方法卡与笔记：本期无入口，数据保留 ----------
  `CREATE TABLE IF NOT EXISTS kpoint_method (
    id TEXT PRIMARY KEY NOT NULL,
    kpointId TEXT NOT NULL,
    kpointName TEXT NOT NULL DEFAULT '',
    subject TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL DEFAULT '',
    nameKey TEXT NOT NULL DEFAULT '',
    keyStepsJson TEXT NOT NULL DEFAULT '[]',
    applicable TEXT NOT NULL DEFAULT '',
    pitfallsJson TEXT NOT NULL DEFAULT '[]',
    sampleCount INTEGER NOT NULL DEFAULT 0,
    lastTitle TEXT NOT NULL DEFAULT '',
    createdAtUtc INTEGER NOT NULL,
    updatedAtUtc INTEGER NOT NULL
  )`,

  `CREATE TABLE IF NOT EXISTS study_note (
    id TEXT PRIMARY KEY NOT NULL,
    kpointId TEXT,
    questionId TEXT,
    source TEXT NOT NULL DEFAULT '',
    kind TEXT NOT NULL DEFAULT 'key',
    title TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL DEFAULT '',
    createdAtUtc INTEGER NOT NULL,
    updatedAtUtc INTEGER NOT NULL
  )`,

  // ---------- 成绩 ----------
  `CREATE TABLE IF NOT EXISTS exam_session (
    id TEXT PRIMARY KEY NOT NULL,
    occurredOn INTEGER NOT NULL,
    kind TEXT NOT NULL DEFAULT '',
    name TEXT,
    gradeSize INTEGER,
    classSize INTEGER,
    gradeRank INTEGER,
    classRank INTEGER,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
  )`,

  // importedAt 是"这一条什么时候录进来的"，学情页据此取"该科最近一次导入"。
  // 不能只靠 session.occurredOn：补录上周的月考时，考试日期早于录入时间。
  `CREATE TABLE IF NOT EXISTS exam_score (
    id TEXT PRIMARY KEY NOT NULL,
    sessionId TEXT NOT NULL,
    subject TEXT NOT NULL,
    score REAL NOT NULL DEFAULT 0,
    fullScore REAL NOT NULL DEFAULT 100,
    gradeRank INTEGER,
    classRank INTEGER,
    importedAt INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (sessionId) REFERENCES exam_session (id) ON DELETE CASCADE
  )`,

  // ---------- 其它 ----------
  `CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL,
    updatedAt INTEGER NOT NULL
  )`,

  `CREATE TABLE IF NOT EXISTS ai_trace (
    id TEXT PRIMARY KEY NOT NULL,
    requestId TEXT NOT NULL DEFAULT '',
    stage TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    promptVer TEXT NOT NULL DEFAULT '',
    inputTokens INTEGER NOT NULL DEFAULT 0,
    outputTokens INTEGER NOT NULL DEFAULT 0,
    latencyMs INTEGER NOT NULL DEFAULT 0,
    ok INTEGER NOT NULL DEFAULT 1,
    errorMsg TEXT,
    createdAt INTEGER NOT NULL
  )`,

  `CREATE TABLE IF NOT EXISTS cache_entry (
    sha256 TEXT NOT NULL,
    promptVer TEXT NOT NULL,
    model TEXT NOT NULL,
    resultJson TEXT NOT NULL,
    sizeBytes INTEGER NOT NULL DEFAULT 0,
    lastAccessAt INTEGER NOT NULL,
    createdAt INTEGER NOT NULL,
    PRIMARY KEY (sha256, promptVer, model)
  )`,

  `CREATE TABLE IF NOT EXISTS forgotten_kpoint (
    kpointId TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    subject TEXT NOT NULL DEFAULT '',
    askCount INTEGER NOT NULL DEFAULT 0,
    lastAskedAt INTEGER NOT NULL
  )`,

  // ---------- 索引：只建查询用得到的，不做无差别索引 ----------
  `CREATE INDEX IF NOT EXISTS idx_mistake_created ON mistake_record (createdAt)`,
  `CREATE INDEX IF NOT EXISTS idx_mistake_question ON mistake_record (questionId)`,
  `CREATE INDEX IF NOT EXISTS idx_mistake_error ON mistake_record (errorType)`,
  `CREATE INDEX IF NOT EXISTS idx_mk_kpoint ON mistake_kpoint (kpointId)`,
  `CREATE INDEX IF NOT EXISTS idx_material_created ON material (createdAtUtc)`,
  `CREATE INDEX IF NOT EXISTS idx_material_kpoint ON material (kpointId)`,
  `CREATE INDEX IF NOT EXISTS idx_exam_subject ON exam_score (subject, importedAt)`,
  `CREATE INDEX IF NOT EXISTS idx_hint_mistake ON hint_log (mistakeId)`,
  `CREATE INDEX IF NOT EXISTS idx_review_mistake ON review_log (mistakeId)`,
];
