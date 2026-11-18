import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import {
  examTrend,
  latestExam,
  listExams,
  saveExam,
  scoredSubjects,
} from "../src/repo/exams";
import { createHarness } from "./support/node-sqlite";

const NOW = 1_700_000_000_000;
const DAY = 86_400_000;

function examInput(
  name: string,
  occurredOn: number,
  scores: Array<{ subject: string; score: number; fullScore: number }>,
  nowUtc: number,
) {
  return { name, occurredOn, kind: "monthly", scores, nowUtc, random: () => 0.5 };
}

describe("学情取数", () => {
  async function seeded() {
    const { db, close } = createHarness();
    await ensureSchema(db);
    // 一场三科的月考
    await saveExam(
      db,
      examInput("十月月考", NOW - 20 * DAY, [
        { subject: "math", score: 120, fullScore: 150 },
        { subject: "chinese", score: 98, fullScore: 150 },
      ], NOW - 10 * DAY),
    );
    // 只录了数学的周测
    await saveExam(
      db,
      examInput("数学周测", NOW - 5 * DAY,
        [{ subject: "math", score: 130, fullScore: 150 }],
        NOW - 2 * DAY),
    );
    return { db, close };
  }

  it("单科取该科最近一次录入，不串科", async () => {
    const { db, close } = await seeded();
    try {
      const math = await latestExam(db, "math");
      expect(math?.name).toBe("数学周测");
      const chinese = await latestExam(db, "chinese");
      expect(chinese?.name).toBe("十月月考");
      expect(chinese?.scores).toHaveLength(1);
      expect(chinese?.scores[0]?.subject).toBe("chinese");
    } finally {
      close();
    }
  });

  it("全部科目只统计各科都录过的场次", async () => {
    const { db, close } = await seeded();
    try {
      const all = await listExams(db, null);
      expect(all).toHaveLength(1);
      expect(all[0]?.name).toBe("十月月考");
    } finally {
      close();
    }
  });

  it("趋势按考试日期升序，单科只算该科", async () => {
    const { db, close } = await seeded();
    try {
      const trend = await examTrend(db, "math");
      expect(trend).toHaveLength(2);
      expect(trend[0]?.occurredOn).toBeLessThan(trend[1]?.occurredOn ?? 0);
      expect(trend[0]?.rate).toBeCloseTo(120 / 150, 5);
      expect(trend[1]?.rate).toBeCloseTo(130 / 150, 5);
    } finally {
      close();
    }
  });

  it("全部科目的趋势是每场平均得分率", async () => {
    const { db, close } = await seeded();
    try {
      const trend = await examTrend(db, null);
      expect(trend).toHaveLength(1);
      expect(trend[0]?.rate).toBeCloseTo((120 / 150 + 98 / 150) / 2, 5);
    } finally {
      close();
    }
  });

  it("科目列表按字母序", async () => {
    const { db, close } = await seeded();
    try {
      expect(await scoredSubjects(db)).toEqual(["chinese", "math"]);
    } finally {
      close();
    }
  });

  it("空库时三处都给安全的空值", async () => {
    const { db, close } = createHarness();
    try {
      await ensureSchema(db);
      expect(await latestExam(db, "math")).toBeNull();
      expect(await latestExam(db, null)).toBeNull();
      expect(await listExams(db, null)).toEqual([]);
      expect(await scoredSubjects(db)).toEqual([]);
    } finally {
      close();
    }
  });

  it("同一场考试录入两科，切到单科各看自己的那条", async () => {
    const { db, close } = await seeded();
    try {
      const math = await latestExam(db, "math");
      const rows = math?.scores ?? [];
      // 单科视图下 scores 只保留该科
      expect(rows.every((row) => row.subject === "math")).toBe(true);
    } finally {
      close();
    }
  });
});
