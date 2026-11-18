import { describe, expect, it } from "vitest";
import { ensureSchema, readSchemaVersion } from "../src/db/migrate";
import { SCHEMA_VERSION } from "../src/db/schema";
import { createHarness } from "./support/node-sqlite";

/**
 * 增量迁移的守卫。
 *
 * 装过 alpha 的设备上，表已经在、但缺新列。`CREATE TABLE IF NOT EXISTS` 对已存在的表
 * 什么都不做，于是查询到运行时才报 "no such column"。这类问题只在升级过的设备上出现，
 * 最难复现——所以必须有用例盯着。
 */
describe("库升级", () => {
  it("老库缺列时补上，且原有数据不丢", async () => {
    const { db, close } = createHarness();
    try {
      // 造一个 v1 的 material 表：没有 stem / explanation
      await db.exec(`CREATE TABLE material (
        id TEXT PRIMARY KEY NOT NULL,
        subject TEXT NOT NULL DEFAULT '',
        kpointId TEXT,
        title TEXT NOT NULL DEFAULT '',
        stepsJson TEXT NOT NULL DEFAULT '[]',
        answer TEXT NOT NULL DEFAULT '',
        source TEXT NOT NULL DEFAULT 'standard_answer',
        origin TEXT NOT NULL DEFAULT '',
        note TEXT NOT NULL DEFAULT '',
        pitfallsJson TEXT NOT NULL DEFAULT '[]',
        imagePath TEXT,
        createdAtUtc INTEGER NOT NULL
      )`);
      await db.run(
        "INSERT INTO material (id, subject, title, answer, createdAtUtc) VALUES (?, ?, ?, ?, ?)",
        ["mt_old", "math", "旧资料", "答案还在", 1],
      );
      await db.exec("PRAGMA user_version = 1");

      expect(await ensureSchema(db)).toBe(SCHEMA_VERSION);
      expect(await readSchemaVersion(db)).toBe(SCHEMA_VERSION);

      const columns = await db.all<{ name: string }>("PRAGMA table_info(material)");
      const names = columns.map((row) => row.name);
      expect(names).toContain("stem");
      expect(names).toContain("explanation");

      const rows = await db.all<{ title: string; answer: string; stem: string }>(
        "SELECT title, answer, stem FROM material WHERE id = ?",
        ["mt_old"],
      );
      expect(rows[0]?.title).toBe("旧资料");
      expect(rows[0]?.answer).toBe("答案还在");
      expect(rows[0]?.stem).toBe("");
    } finally {
      close();
    }
  });

  it("重复调用是幂等的", async () => {
    const { db, close } = await seededTwice();
    try {
      const first = await ensureSchema(db);
      const second = await ensureSchema(db);
      expect(first).toBe(second);
      const columns = await db.all<{ name: string }>("PRAGMA table_info(material)");
      expect(columns.filter((row) => row.name === "stem")).toHaveLength(1);
    } finally {
      close();
    }
  });
});

async function seededTwice() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  return { db, close };
}
