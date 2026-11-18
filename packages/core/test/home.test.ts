import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import { homeStats, weakKpoints } from "../src/repo/home";
import { createHarness } from "./support/node-sqlite";

const NOW = 1_700_000_000_000;
const DAY = 86_400_000;

async function seeded() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  const kpoints = [
    ["KP_MATH_0001", "函数求值", "math"],
    ["KP_MATH_0002", "二次函数", "math"],
    ["KP_CN_0001", "文言实词", "chinese"],
  ];
  for (const [id, name, subject] of kpoints) {
    await db.run(
      "INSERT INTO knowledge_point (id, name, subject, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      [id, name, subject, "章", 1, "", "", "[]"],
    );
  }
  return { db, close };
}

async function addMistake(
  db: Awaited<ReturnType<typeof seeded>>["db"],
  id: string,
  createdAt: number,
  kpointIds: string[] = [],
): Promise<void> {
  await db.run(
    "INSERT INTO question (id, subject, createdAt) VALUES (?, ?, ?)",
    [`q_${id}`, "math", createdAt],
  );
  await db.run(
    "INSERT INTO mistake_record (id, questionId, createdAt, status) VALUES (?, ?, ?, ?)",
    [id, `q_${id}`, createdAt, ""],
  );
  for (const kpointId of kpointIds) {
    await db.run(
      "INSERT INTO mistake_kpoint (mistakeId, kpointId, confidence) VALUES (?, ?, ?)",
      [id, kpointId, 0.9],
    );
  }
}

describe("首页三个数字", () => {
  it("空库时是 0 / 0 / 无考试，不报错", async () => {
    const { db, close } = await seeded();
    try {
      expect(await homeStats(db, NOW)).toEqual({
        mistakesLast7Days: 0,
        masteredKpoints: 0,
        latestExam: null,
      });
    } finally {
      close();
    }
  });

  it("近 7 天错题只数窗口内的", async () => {
    const { db, close } = await seeded();
    try {
      await addMistake(db, "m1", NOW - 1 * DAY);
      await addMistake(db, "m2", NOW - 3 * DAY);
      await addMistake(db, "m3", NOW - 10 * DAY);
      expect((await homeStats(db, NOW)).mistakesLast7Days).toBe(2);
    } finally {
      close();
    }
  });

  it("已掌握知识点按分数线统计", async () => {
    const { db, close } = await seeded();
    try {
      await db.run("INSERT INTO mastery (kpointId, score, historyJson, updatedAt) VALUES (?, ?, ?, ?)", [
        "KP_MATH_0001",
        0.8,
        "[]",
        NOW,
      ]);
      await db.run("INSERT INTO mastery (kpointId, score, historyJson, updatedAt) VALUES (?, ?, ?, ?)", [
        "KP_MATH_0002",
        0.4,
        "[]",
        NOW,
      ]);
      expect((await homeStats(db, NOW)).masteredKpoints).toBe(1);
    } finally {
      close();
    }
  });

  it("最近一次考试按录入时间取，不按考试日期", async () => {
    const { db, close } = await seeded();
    try {
      // 补录的那条：考试日期更早，但录入时间更晚，应该它排在最前
      await db.run(
        "INSERT INTO exam_session (id, occurredOn, kind, name, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
        ["s1", NOW - 30 * DAY, "monthly", "旧月考", NOW - 10 * DAY, NOW - 10 * DAY],
      );
      await db.run(
        "INSERT INTO exam_session (id, occurredOn, kind, name, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
        ["s2", NOW - 40 * DAY, "monthly", "补录月考", NOW - 1 * DAY, NOW - 1 * DAY],
      );
      await db.run(
        "INSERT INTO exam_score (id, sessionId, subject, score, fullScore, importedAt) VALUES (?, ?, ?, ?, ?, ?)",
        ["sc1", "s1", "math", 120, 150, NOW - 10 * DAY],
      );
      await db.run(
        "INSERT INTO exam_score (id, sessionId, subject, score, fullScore, importedAt) VALUES (?, ?, ?, ?, ?, ?)",
        ["sc2", "s2", "chinese", 98, 150, NOW - 1 * DAY],
      );

      const stats = await homeStats(db, NOW);
      expect(stats.latestExam?.subject).toBe("chinese");
      expect(stats.latestExam?.rate).toBeCloseTo(98 / 150, 5);
    } finally {
      close();
    }
  });

  it("满分缺失时不除零", async () => {
    const { db, close } = await seeded();
    try {
      await db.run(
        "INSERT INTO exam_session (id, occurredOn, kind, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?)",
        ["s1", NOW, "monthly", NOW, NOW],
      );
      await db.run(
        "INSERT INTO exam_score (id, sessionId, subject, score, fullScore, importedAt) VALUES (?, ?, ?, ?, ?, ?)",
        ["sc1", "s1", "math", 0, 0, NOW],
      );
      expect((await homeStats(db, NOW)).latestExam?.rate).toBe(0);
    } finally {
      close();
    }
  });
});

describe("不熟知识点", () => {
  it("没有行为数据就不进列表：不熟要由错题或提问证明", async () => {
    const { db, close } = await seeded();
    try {
      expect(await weakKpoints(db)).toEqual([]);
    } finally {
      close();
    }
  });

  it("按错题数排序，最多五条", async () => {
    const { db, close } = await seeded();
    try {
      await addMistake(db, "m1", NOW, ["KP_MATH_0002"]);
      await addMistake(db, "m2", NOW, ["KP_MATH_0002"]);
      await addMistake(db, "m3", NOW, ["KP_CN_0001"]);
      const list = await weakKpoints(db);
      expect(list[0]?.name).toBe("二次函数");
      expect(list[0]?.mistakeCount).toBe(2);
      expect(list).toHaveLength(2);
    } finally {
      close();
    }
  });

  it("陪伴页问过的也算，但权重低于做错", async () => {
    const { db, close } = await seeded();
    try {
      await addMistake(db, "m1", NOW, ["KP_CN_0001"]);
      await db.run(
        "INSERT INTO forgotten_kpoint (kpointId, name, subject, askCount, lastAskedAt) VALUES (?, ?, ?, ?, ?)",
        ["KP_MATH_0001", "函数求值", "math", 5, NOW],
      );
      const list = await weakKpoints(db);
      // 文言实词：1 次错题 = 1 分；函数求值：5 次提问 × 0.5 = 2.5 分
      expect(list[0]?.id).toBe("KP_MATH_0001");
      expect(list[1]?.id).toBe("KP_CN_0001");
    } finally {
      close();
    }
  });

  it("limit 生效", async () => {
    const { db, close } = await seeded();
    try {
      await addMistake(db, "m1", NOW, ["KP_MATH_0001", "KP_MATH_0002", "KP_CN_0001"]);
      expect(await weakKpoints(db, 2)).toHaveLength(2);
    } finally {
      close();
    }
  });
});
