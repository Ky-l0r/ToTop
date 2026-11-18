import { describe, expect, it } from "vitest";
import { ensureSchema, readSchemaVersion } from "../src/db/migrate";
import { SCHEMA_VERSION } from "../src/db/schema";
import { createHarness } from "./support/node-sqlite";

describe("1.0 库结构", () => {
  it("建表后写入版本号", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      expect(await readSchemaVersion(db)).toBe(SCHEMA_VERSION);
    } finally {
      close();
    }
  });

  it("可重复调用，不重复建表也不报错", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      await ensureSchema(db);
      const rows = await db.all<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'mistake_record'",
      );
      expect(rows).toHaveLength(1);
    } finally {
      close();
    }
  });

  it("题、错题、资料、成绩四张主表都在", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      const rows = await db.all<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
      );
      const names = rows.map((row) => row.name);
      expect(names).toEqual(
        expect.arrayContaining([
          "question",
          "mistake_record",
          "solution_revision",
          "material",
          "exam_session",
          "exam_score",
          "knowledge_point",
          "mastery",
          "settings",
        ]),
      );
      // 无后端时代的离线队列与日汇总表不迁过来，留着只会有人往里写。
      expect(names).not.toContain("outbox");
      expect(names).not.toContain("daily_stats");
      expect(names).not.toContain("report");
    } finally {
      close();
    }
  });

  it("solution_revision 一道题只留一版，重复写入覆盖旧值", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      await db.run(
        "INSERT INTO question (id, subject, createdAt) VALUES (?, ?, ?)",
        ["q1", "math", 1],
      );
      await db.run(
        "INSERT INTO mistake_record (id, questionId, createdAt) VALUES (?, ?, ?)",
        ["m1", "q1", 1],
      );
      const insert =
        "INSERT OR REPLACE INTO solution_revision (mistakeId, stepsJson, finalAnswer, reason, createdAt) VALUES (?, ?, ?, ?, ?)";
      await db.run(insert, ["m1", "[]", "A", "standard_answer", 1]);
      await db.run(insert, ["m1", "[{\"idx\":1}]", "C", "dialog_correction", 2]);
      const rows = await db.all<{ finalAnswer: string; reason: string }>(
        "SELECT finalAnswer, reason FROM solution_revision WHERE mistakeId = ?",
        ["m1"],
      );
      expect(rows).toHaveLength(1);
      expect(rows[0]?.finalAnswer).toBe("C");
      expect(rows[0]?.reason).toBe("dialog_correction");
    } finally {
      close();
    }
  });

  it("删掉错题时旧解答跟着删，不留孤儿行", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      await db.run("INSERT INTO question (id, subject, createdAt) VALUES (?, ?, ?)", [
        "q1",
        "math",
        1,
      ]);
      await db.run("INSERT INTO mistake_record (id, questionId, createdAt) VALUES (?, ?, ?)", [
        "m1",
        "q1",
        1,
      ]);
      await db.run(
        "INSERT INTO solution_revision (mistakeId, stepsJson, finalAnswer, reason, createdAt) VALUES (?, ?, ?, ?, ?)",
        ["m1", "[]", "A", "standard_answer", 1],
      );
      await db.run("DELETE FROM mistake_record WHERE id = ?", ["m1"]);
      const rows = await db.all<{ count: number }>(
        "SELECT COUNT(*) AS count FROM solution_revision",
      );
      expect(rows[0]?.count).toBe(0);
    } finally {
      close();
    }
  });

  it("成绩带录入时间，供学情页取该科最近一次", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      const columns = await db.all<{ name: string }>("PRAGMA table_info(exam_score)");
      expect(columns.map((column) => column.name)).toContain("importedAt");
    } finally {
      close();
    }
  });
});
