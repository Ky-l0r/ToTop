import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import { INITIAL_MASTERY } from "../src/mastery/algorithm";
import {
  applyMastery,
  chapterHeat,
  kpointDetail,
  listKpoints,
  markMistakeMastered,
} from "../src/repo/knowledge";
import { saveSolvedQuestion, type SaveSolvedInput } from "../src/repo/mistakes";
import { createHarness } from "./support/node-sqlite";

const NOW = 1_700_000_000_000;

async function seeded() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  const rows: Array<[string, string, string, string]> = [
    ["KP_MATH_0001", "函数求值", "math", "函数"],
    ["KP_MATH_0002", "二次函数", "math", "函数"],
    ["KP_MATH_0003", "数列求和", "math", "数列"],
    ["KP_CN_0001", "文言实词", "chinese", "文言文"],
  ];
  for (const [id, name, subject, chapter] of rows) {
    await db.run(
      "INSERT INTO knowledge_point (id, name, subject, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
      [id, name, subject, chapter, 1, "", "", "[]"],
    );
  }
  return { db, close };
}

function solveInput(kpointIds: string[]): SaveSolvedInput {
  return {
    spec: {
      subject: "math",
      grade: "高一",
      title: "二次函数最值",
      questionType: "大题",
      stem: "求最小值",
      known: [],
      ask: "求最小值",
      options: [],
      choiceType: "none",
      hasFigure: false,
      stemLatex: "",
      handwriting: "none",
      handwritingText: "",
      progress: [],
      warnings: [],
    },
    solution: {
      steps: [
        { idx: 1, content: "a", why: "" },
        { idx: 2, content: "b", why: "" },
      ],
      finalAnswer: "0",
      method: "",
      check: "",
      confidence: 0.9,
    },
    report: null,
    kpoints: {
      kpoints: kpointIds.map((id) => ({ id, name: id, confidence: 0.9, usage: "" })),
      primaryId: kpointIds[0] ?? null,
      unmatched: false,
    },
    mode: "GUIDED",
    nowUtc: NOW,
    random: () => 0.5,
  };
}

describe("知识点列表", () => {
  it("没有掌握度记录的知识点用初始值，并标记 hasMastery=false", async () => {
    const { db, close } = await seeded();
    try {
      const list = await listKpoints(db, { subject: "math" });
      expect(list).toHaveLength(3);
      expect(list.every((item) => item.score === INITIAL_MASTERY)).toBe(true);
      expect(list.every((item) => !item.hasMastery)).toBe(true);
    } finally {
      close();
    }
  });

  it("按科目与区间筛", async () => {
    const { db, close } = await seeded();
    try {
      await db.run(
        "INSERT INTO mastery (kpointId, score, historyJson, updatedAt) VALUES (?, ?, ?, ?)",
        ["KP_MATH_0003", 0.2, "[]", NOW],
      );
      expect(await listKpoints(db, { subject: "chinese" })).toHaveLength(1);
      const weak = await listKpoints(db, { subject: "math", band: "weak" });
      expect(weak.map((item) => item.id)).toEqual(["KP_MATH_0003"]);
    } finally {
      close();
    }
  });

  it("带出错题数", async () => {
    const { db, close } = await seeded();
    try {
      await saveSolvedQuestion(db, solveInput(["KP_MATH_0002"]));
      const list = await listKpoints(db, { subject: "math" });
      const target = list.find((item) => item.id === "KP_MATH_0002");
      expect(target?.mistakeCount).toBe(1);
    } finally {
      close();
    }
  });
});

describe("章节热力", () => {
  it("按章节取平均，没记录时是初始值", async () => {
    const { db, close } = await seeded();
    try {
      await db.run(
        "INSERT INTO mastery (kpointId, score, historyJson, updatedAt) VALUES (?, ?, ?, ?)",
        ["KP_MATH_0001", 0.9, "[]", NOW],
      );
      const heat = await chapterHeat(db, "math");
      const fnChapter = heat.find((item) => item.chapter === "函数");
      expect(fnChapter?.count).toBe(2);
      // (0.9 + 0.5) / 2
      expect(fnChapter?.score).toBeCloseTo(0.7, 5);
    } finally {
      close();
    }
  });

  it("按科目过滤", async () => {
    const { db, close } = await seeded();
    try {
      const heat = await chapterHeat(db, "chinese");
      expect(heat).toHaveLength(1);
      expect(heat[0]?.chapter).toBe("文言文");
    } finally {
      close();
    }
  });
});

describe("掌握度写回", () => {
  it("错题落库会自动降低该知识点的掌握度", async () => {
    const { db, close } = await seeded();
    try {
      await saveSolvedQuestion(db, solveInput(["KP_MATH_0002"]));
      const list = await listKpoints(db, { subject: "math" });
      const target = list.find((item) => item.id === "KP_MATH_0002");
      expect(target?.hasMastery).toBe(true);
      expect(target?.score).toBeLessThan(INITIAL_MASTERY);
    } finally {
      close();
    }
  });

  it("历史只留最近 20 条", async () => {
    const { db, close } = await seeded();
    try {
      for (let i = 0; i < 25; i += 1) {
        await applyMastery(db, "KP_MATH_0001", "correct", NOW + i);
      }
      const detail = await kpointDetail(db, "KP_MATH_0001");
      expect(detail?.history).toHaveLength(20);
      expect(detail?.history.at(-1)?.kind).toBe("correct");
    } finally {
      close();
    }
  });

  it("详情带该知识点的错题列表", async () => {
    const { db, close } = await seeded();
    try {
      await saveSolvedQuestion(db, solveInput(["KP_MATH_0002"]));
      const detail = await kpointDetail(db, "KP_MATH_0002");
      expect(detail?.mistakes).toHaveLength(1);
      expect(detail?.mistakes[0]?.stem).toContain("求最小值");
    } finally {
      close();
    }
  });

  it("学生说「这题我会了」会给每个关联知识点记一次做对", async () => {
    const { db, close } = await seeded();
    try {
      await applyMastery(db, "KP_MATH_0001", "wrong", NOW);
      const before = (await kpointDetail(db, "KP_MATH_0001"))?.score ?? 0;

      const ids = await saveSolvedQuestion(db, solveInput(["KP_MATH_0001", "KP_MATH_0002"]));
      const touched = await markMistakeMastered(db, ids.mistakeId, NOW + 1000);

      expect(touched).toBe(2);
      const after = (await kpointDetail(db, "KP_MATH_0001"))?.score ?? 0;
      expect(after).toBeGreaterThan(before);
    } finally {
      close();
    }
  });

  it("没有关联知识点的错题点了也没副作用", async () => {
    const { db, close } = await seeded();
    try {
      const ids = await saveSolvedQuestion(db, solveInput([]));
      expect(await markMistakeMastered(db, ids.mistakeId, NOW)).toBe(0);
    } finally {
      close();
    }
  });

  it("查不存在的知识点返回 null", async () => {
    const { db, close } = await seeded();
    try {
      expect(await kpointDetail(db, "KP_NOT_EXIST")).toBeNull();
    } finally {
      close();
    }
  });
});
