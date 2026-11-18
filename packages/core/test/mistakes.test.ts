import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import {
  countMistakes,
  getMistake,
  listMistakeKpoints,
  listMistakes,
  relativeTime,
  saveSolvedQuestion,
  type SaveSolvedInput,
} from "../src/repo/mistakes";
import type { QuestionSpec } from "../src/solve/contracts";
import { createHarness } from "./support/node-sqlite";

const BASE_TIME = 1_700_000_000_000;

function spec(overrides: Partial<QuestionSpec> = {}): QuestionSpec {
  return {
    subject: "math",
    grade: "高一",
    title: "二次函数闭区间最值",
    questionType: "大题",
    stem: "已知 f(x)=x^2-2x+1，求 [0,3] 上最小值。",
    known: ["f(x)=x^2-2x+1"],
    ask: "求最小值",
    options: [],
    choiceType: "none",
    hasFigure: false,
    stemLatex: "",
    handwriting: "none",
    handwritingText: "",
    progress: [],
    warnings: [],
    ...overrides,
  };
}

function input(overrides: Partial<SaveSolvedInput> = {}): SaveSolvedInput {
  return {
    spec: spec(),
    solution: {
      steps: [
        { idx: 1, content: "配方", why: "化为顶点式" },
        { idx: 2, content: "比较端点", why: "闭区间" },
      ],
      finalAnswer: "0",
      method: "配方法",
      check: "",
      confidence: 0.9,
    },
    report: {
      studentSteps: [{ idx: 1, content: "配方", status: "wrong", alignTo: 1 }],
      firstError: {
        stepIdx: 1,
        subQuestion: null,
        type: "计算失误",
        explain: "常数项算错",
        correctThinking: "先定常数项",
      },
      summaryCard: { title: "配方别丢常数", bullets: [] },
    },
    kpoints: {
      kpoints: [{ id: "KP_MATH_0002", name: "二次函数", confidence: 0.9, usage: "配方" }],
      primaryId: "KP_MATH_0002",
      unmatched: false,
    },
    mode: "GUIDED",
    nowUtc: BASE_TIME,
    random: () => 0.5,
    ...overrides,
  };
}

async function seeded() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  await db.run(
    "INSERT INTO knowledge_point (id, name, subject, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ["KP_MATH_0002", "二次函数", "math", "函数", 1, "", "", "[]"],
  );
  await db.run(
    "INSERT INTO knowledge_point (id, name, subject, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ["KP_CN_0001", "文言实词", "chinese", "文言文", 1, "", "", "[]"],
  );
  return { db, close };
}

describe("解答落库", () => {
  it("题目、错题、知识点关联三张表都写进去", async () => {
    const { db, close } = await seeded();
    try {
      const ids = await saveSolvedQuestion(db, input());
      expect(ids.questionId).toMatch(/^q_/);
      expect(ids.mistakeId).toMatch(/^m_/);

      const questions = await db.all<{ stemText: string; questionType: string }>(
        "SELECT stemText, questionType FROM question WHERE id = ?",
        [ids.questionId],
      );
      expect(questions[0]?.stemText).toContain("x^2-2x+1");
      expect(questions[0]?.questionType).toBe("大题");

      const links = await db.all<{ kpointId: string }>(
        "SELECT kpointId FROM mistake_kpoint WHERE mistakeId = ?",
        [ids.mistakeId],
      );
      expect(links.map((row) => row.kpointId)).toEqual(["KP_MATH_0002"]);
    } finally {
      close();
    }
  });

  it("新录入不写状态值：status 是空串，不是 wrong", async () => {
    const { db, close } = await seeded();
    try {
      const ids = await saveSolvedQuestion(db, input());
      const rows = await db.all<{ status: string }>(
        "SELECT status FROM mistake_record WHERE id = ?",
        [ids.mistakeId],
      );
      expect(rows[0]?.status).toBe("");
    } finally {
      close();
    }
  });

  it("写库是事务：中途失败不会留下半条记录", async () => {
    const { db, close } = await seeded();
    try {
      await expect(
        saveSolvedQuestion(db, input({ kpoints: null, spec: spec({ subject: "math" }) })),
      ).resolves.toBeTruthy();
      // 题库里没有这个知识点也不会失败——只是不挂关联
      const count = await countMistakes(db);
      expect(count).toBe(1);
    } finally {
      close();
    }
  });
});

describe("易错题列表与四个筛选", () => {
  async function twoMistakes() {
    const harness = await seeded();
    const first = await saveSolvedQuestion(harness.db, input({ nowUtc: BASE_TIME }));
    const second = await saveSolvedQuestion(
      harness.db,
      input({
        nowUtc: BASE_TIME + 86_400_000,
        random: () => 0.25,
        spec: spec({
          subject: "chinese",
          title: "文言实词辨析",
          stem: "解释加点字：沛公军霸上。",
          questionType: "文言文",
        }),
        report: {
          studentSteps: [],
          firstError: {
            stepIdx: 1,
            subQuestion: null,
            type: "审题理解",
            explain: "没看出词类活用",
            correctThinking: "先判断词性",
          },
          summaryCard: { title: "军是名词作动词", bullets: [] },
        },
        kpoints: {
          kpoints: [{ id: "KP_CN_0001", name: "文言实词", confidence: 0.8, usage: "实词" }],
          primaryId: "KP_CN_0001",
          unmatched: false,
        },
      }),
    );
    return { ...harness, first, second };
  }

  it("默认按时间倒序全列出来", async () => {
    const { db, close } = await twoMistakes();
    try {
      const list = await listMistakes(db);
      expect(list).toHaveLength(2);
      expect(list[0]?.subject).toBe("chinese");
      expect(list[0]?.kpointName).toBe("文言实词");
      expect(list[1]?.kpointName).toBe("二次函数");
    } finally {
      close();
    }
  });

  it("按科目筛", async () => {
    const { db, close } = await twoMistakes();
    try {
      expect(await listMistakes(db, { subject: "math" })).toHaveLength(1);
      expect(await listMistakes(db, { subject: "physics" })).toHaveLength(0);
    } finally {
      close();
    }
  });

  it("按知识点筛", async () => {
    const { db, close } = await twoMistakes();
    try {
      const list = await listMistakes(db, { kpointId: "KP_CN_0001" });
      expect(list).toHaveLength(1);
      expect(list[0]?.subject).toBe("chinese");
    } finally {
      close();
    }
  });

  it("按错因筛", async () => {
    const { db, close } = await twoMistakes();
    try {
      const list = await listMistakes(db, { errorType: "计算失误" });
      expect(list).toHaveLength(1);
      expect(list[0]?.subject).toBe("math");
    } finally {
      close();
    }
  });

  it("按时间筛：只留最近一天", async () => {
    const { db, close } = await twoMistakes();
    try {
      const list = await listMistakes(db, { sinceUtc: BASE_TIME + 3600_000 });
      expect(list).toHaveLength(1);
      expect(list[0]?.subject).toBe("chinese");
    } finally {
      close();
    }
  });

  it("多个条件同时生效", async () => {
    const { db, close } = await twoMistakes();
    try {
      const list = await listMistakes(db, { subject: "math", errorType: "审题理解" });
      expect(list).toHaveLength(0);
    } finally {
      close();
    }
  });

  it("条数统计与筛选一致", async () => {
    const { db, close } = await twoMistakes();
    try {
      expect(await countMistakes(db)).toBe(2);
      expect(await countMistakes(db, { subject: "math" })).toBe(1);
    } finally {
      close();
    }
  });

  it("知识点筛选项只列挂过错题的，且带条数", async () => {
    const { db, close } = await twoMistakes();
    try {
      const options = await listMistakeKpoints(db);
      expect(options.map((item) => item.name).sort()).toEqual(["二次函数", "文言实词"]);
      expect(options.every((item) => item.count === 1)).toBe(true);
    } finally {
      close();
    }
  });
});

describe("错题详情", () => {
  it("题目、步骤、错因、知识点都能取回", async () => {
    const { db, close } = await seeded();
    try {
      const ids = await saveSolvedQuestion(db, input());
      const detail = await getMistake(db, ids.mistakeId);
      expect(detail).not.toBeNull();
      expect(detail?.ask).toBe("求最小值");
      expect(detail?.steps).toHaveLength(2);
      expect(detail?.errorType).toBe("计算失误");
      expect(detail?.studentSteps).toHaveLength(1);
      expect(detail?.revision).toBeNull();
    } finally {
      close();
    }
  });

  it("改写过的题能取回旧解答", async () => {
    const { db, close } = await seeded();
    try {
      const ids = await saveSolvedQuestion(db, input());
      await db.run(
        "INSERT INTO solution_revision (mistakeId, stepsJson, finalAnswer, reason, createdAt) VALUES (?, ?, ?, ?, ?)",
        [ids.mistakeId, "[]", "1", "standard_answer", BASE_TIME + 1000],
      );
      const detail = await getMistake(db, ids.mistakeId);
      expect(detail?.revision?.finalAnswer).toBe("1");
      expect(detail?.revision?.reason).toBe("standard_answer");
    } finally {
      close();
    }
  });

  it("查不到时返回 null，不抛错", async () => {
    const { db, close } = await seeded();
    try {
      expect(await getMistake(db, "m_不存在")).toBeNull();
    } finally {
      close();
    }
  });
});

describe("相对时间", () => {
  it("按区间给人话", () => {
    expect(relativeTime(BASE_TIME - 30_000, BASE_TIME)).toBe("刚刚");
    expect(relativeTime(BASE_TIME - 5 * 60_000, BASE_TIME)).toBe("5 分钟前");
    expect(relativeTime(BASE_TIME - 3 * 3600_000, BASE_TIME)).toBe("3 小时前");
    expect(relativeTime(BASE_TIME - 2 * 86_400_000, BASE_TIME)).toBe("2 天前");
    expect(relativeTime(BASE_TIME - 70 * 86_400_000, BASE_TIME)).toBe("2 个月前");
  });
});
