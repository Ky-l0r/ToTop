import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import { importLegacyDb, LegacyImportError, looksLikeLegacyDb } from "../src/db/legacy";
import type { SqlExecutor } from "../src/db/executor";
import { createHarness } from "./support/node-sqlite";
import { legacyCreateStatements } from "./support/legacy-ddl";

const NOW = 1_700_000_000_000;
const SESSION_CREATED_AT = 1_690_000_000_000;

const LEGACY_TABLES = [
  "question",
  "mistake_record",
  "knowledge_point",
  "mistake_kpoint",
  "mastery",
  "review_log",
  "hint_log",
  "settings",
  "exam_session",
  "exam_score",
  "learned_material",
] as const;

/** 搭一个 0.8.0 的库：建表来自 Room 快照，数据是手写的两题一卡。 */
async function buildLegacy(source: SqlExecutor): Promise<void> {
  for (const statement of legacyCreateStatements(LEGACY_TABLES)) {
    await source.exec(statement);
  }

  await source.run(
    `INSERT INTO question (id, subject, grade, stemText, knownJson, ask, optionsJson, hasFigure,
       imagePath, imageSha256, sourceType, createdAt, selfMasteredAt, imagePathsJson)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ["q1", "math", "高一", "已知 f(x)=x^2，求 f(2)", "[]", "f(2)", "[]", 0,
      "/img/q1.jpg", "sha1", "photo", 1000, null, "[]"],
  );
  await source.run(
    `INSERT INTO question (id, subject, grade, stemText, knownJson, ask, optionsJson, hasFigure,
       imagePath, imageSha256, sourceType, createdAt, selfMasteredAt, imagePathsJson)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ["q2", "chinese", "高一", "下列词语使用正确的是", "[]", "选择", "[\"A\",\"B\"]", 0,
      null, null, "text", 2000, 3000, "[]"],
  );

  await source.run(
    `INSERT INTO mistake_record (id, questionId, studentStepsJson, firstErrorStepIdx, errorType,
       errorExplain, correctPathJson, finalAnswer, mode, createdAt, recurrenceIndex, status, source)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ["m1", "q1", "[]", 1, "CALC", "代入算错", "[]", "4", "DIRECT", 1100, 0, "wrong", "solve"],
  );
  await source.run(
    `INSERT INTO mistake_record (id, questionId, studentStepsJson, firstErrorStepIdx, errorType,
       errorExplain, correctPathJson, finalAnswer, mode, createdAt, recurrenceIndex, status, source)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ["m2", "q2", "[]", null, "", "", "[]", "B", "GUIDED", 2100, 1, "disputed", "solve"],
  );

  await source.run(
    "INSERT INTO knowledge_point (id, name, subject, parentId, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ["KP_MATH_0001", "函数求值", "math", null, "函数", 1, "代入", "先看定义域", "[]"],
  );
  await source.run(
    "INSERT INTO knowledge_point (id, name, subject, parentId, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ["KP_MATH_0002", "二次函数", "math", "KP_MATH_0001", "函数", 2, "配方", "看开口", "[]"],
  );

  await source.run("INSERT INTO mistake_kpoint (mistakeId, kpointId, confidence) VALUES (?, ?, ?)", [
    "m1",
    "KP_MATH_0002",
    0.9,
  ]);
  await source.run("INSERT INTO mastery (kpointId, score, historyJson, updatedAt) VALUES (?, ?, ?, ?)", [
    "KP_MATH_0002",
    0.42,
    "[]",
    2500,
  ]);
  await source.run(
    "INSERT INTO review_log (id, mistakeId, reviewedAt, selfResult, source) VALUES (?, ?, ?, ?, ?)",
    ["r1", "m1", 2600, "again", "self"],
  );
  await source.run("INSERT INTO hint_log (id, mistakeId, level, createdAt) VALUES (?, ?, ?, ?)", [
    "h1",
    "m1",
    2,
    1200,
  ]);

  await source.run("INSERT INTO settings (key, value, updatedAt) VALUES (?, ?, ?)", ["mode", "DIRECT", 900]);
  await source.run("INSERT INTO settings (key, value, updatedAt) VALUES (?, ?, ?)", [
    "apiKey",
    "sk-should-not-migrate",
    900,
  ]);

  await source.run(
    "INSERT INTO exam_session (id, occurredOn, kind, name, gradeSize, classSize, gradeRank, classRank, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ["s1", 1_680_000_000_000, "monthly", "十月月考", 500, 40, 120, 8, SESSION_CREATED_AT, SESSION_CREATED_AT],
  );
  await source.run(
    "INSERT INTO exam_score (id, sessionId, subject, score, fullScore, gradeRank, classRank) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ["sc1", "s1", "math", 112, 150, 120, 8],
  );
  await source.run(
    "INSERT INTO exam_score (id, sessionId, subject, score, fullScore, gradeRank, classRank) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ["sc2", "s1", "chinese", 98, 150, 200, 15],
  );

  // 老资料库的一张方法卡：title 为空，要用 method 兜底
  await source.run(
    `INSERT INTO learned_material (id, subject, kpointId, kpointName, title, method, keyStepsJson,
       answer, pitfallsJson, sourceLabel, imagePath, createdAtUtc)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ["mat1", "math", "KP_MATH_0002", "二次函数", "", "配方法", "[\"配方\",\"取值\"]",
      "顶点 (1,2)", "[\"忘记变号\"]", "手写", null, 1500],
  );
}

async function prepare(): Promise<{
  source: SqlExecutor;
  target: SqlExecutor;
  close: () => void;
}> {
  const legacy = createHarness();
  const fresh = createHarness();
  await buildLegacy(legacy.db);
  await ensureSchema(fresh.db);
  return {
    source: legacy.db,
    target: fresh.db,
    close: () => {
      legacy.close();
      fresh.close();
    },
  };
}

describe("从 0.8.0 备份库导入", () => {
  it("认得旧库，认不出空库", async () => {
    const { source, close } = await prepare();
    const empty = createHarness();
    try {
      expect(await looksLikeLegacyDb(source)).toBe(true);
      expect(await looksLikeLegacyDb(empty.db)).toBe(false);
      await expect(importLegacyDb(empty.db, source, { nowUtc: NOW })).rejects.toBeInstanceOf(
        LegacyImportError,
      );
    } finally {
      close();
      empty.close();
    }
  });

  it("题目、错题、知识点关联、掌握度都进了新库", async () => {
    const { source, target, close } = await prepare();
    try {
      const report = await importLegacyDb(source, target, { nowUtc: NOW });
      expect(report.counts.question).toBe(2);
      expect(report.counts.mistake_record).toBe(2);
      expect(report.counts.mistake_kpoint).toBe(1);
      expect(report.counts.mastery).toBe(1);

      const mistakes = await target.all<{ id: string; status: string; mode: string }>(
        "SELECT id, status, mode FROM mistake_record ORDER BY id",
      );
      expect(mistakes.map((row) => row.id)).toEqual(["m1", "m2"]);
      // 状态列照抄进来只为保真，界面不再读它。
      expect(mistakes[1]?.status).toBe("disputed");
      expect(mistakes[0]?.mode).toBe("DIRECT");

      const question = await target.all<{ questionType: string; choiceType: string }>(
        "SELECT questionType, choiceType FROM question WHERE id = ?",
        ["q1"],
      );
      // 旧库没有这两个字段，必须落到默认值而不是 null
      expect(question[0]?.questionType).toBe("");
      expect(question[0]?.choiceType).toBe("");
    } finally {
      close();
    }
  });

  it("成绩带上录入时间（取考试场次的 createdAt，不是考试日期）", async () => {
    const { source, target, close } = await prepare();
    try {
      await importLegacyDb(source, target, { nowUtc: NOW });
      const scores = await target.all<{ subject: string; importedAt: number }>(
        "SELECT subject, importedAt FROM exam_score ORDER BY subject",
      );
      expect(scores).toHaveLength(2);
      for (const row of scores) {
        expect(row.importedAt).toBe(SESSION_CREATED_AT);
      }
    } finally {
      close();
    }
  });

  it("旧方法卡变成资料库里的 legacy_card，标题为空时用方法名兜底", async () => {
    const { source, target, close } = await prepare();
    try {
      const report = await importLegacyDb(source, target, { nowUtc: NOW });
      expect(report.counts.material).toBe(1);
      const rows = await target.all<{ title: string; source: string; answer: string }>(
        "SELECT title, source, answer FROM material",
      );
      expect(rows[0]).toMatchObject({ title: "配方法", source: "legacy_card", answer: "顶点 (1,2)" });
    } finally {
      close();
    }
  });

  it("敏感设置项跟着备份走不进来，其余设置照搬", async () => {
    const { source, target, close } = await prepare();
    try {
      const report = await importLegacyDb(source, target, { nowUtc: NOW });
      expect(report.filteredSettings).toBe(1);
      const settings = await target.all<{ key: string; value: string }>(
        "SELECT key, value FROM settings ORDER BY key",
      );
      const keys = settings.map((row) => row.key);
      expect(keys).toContain("mode");
      expect(keys).not.toContain("apiKey");
      expect(settings.find((row) => row.key === "mode")?.value).toBe("DIRECT");
    } finally {
      close();
    }
  });

  it("缺表不报错，只在报告里记一笔", async () => {
    const { source, target, close } = await prepare();
    try {
      const report = await importLegacyDb(source, target, { nowUtc: NOW });
      expect(report.missingTables).toContain("study_note");
      expect(report.missingTables).not.toContain("question");
    } finally {
      close();
    }
  });

  it("重复导入不产生重复行", async () => {
    const { source, target, close } = await prepare();
    try {
      await importLegacyDb(source, target, { nowUtc: NOW });
      await importLegacyDb(source, target, { nowUtc: NOW + 1000 });
      const rows = await target.all<{ count: number }>("SELECT COUNT(*) AS count FROM question");
      expect(rows[0]?.count).toBe(2);
      const materials = await target.all<{ count: number }>(
        "SELECT COUNT(*) AS count FROM material",
      );
      expect(materials[0]?.count).toBe(1);
    } finally {
      close();
    }
  });

  it("导入时间写进设置，设置页据此显示已迁移", async () => {
    const { source, target, close } = await prepare();
    try {
      await importLegacyDb(source, target, { nowUtc: NOW });
      const rows = await target.all<{ value: string }>(
        "SELECT value FROM settings WHERE key = ?",
        ["import.legacy.at"],
      );
      expect(rows[0]?.value).toBe(String(NOW));
    } finally {
      close();
    }
  });
});
