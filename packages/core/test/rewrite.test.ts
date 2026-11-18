import { describe, expect, it } from "vitest";
import { ensureSchema } from "../src/db/migrate";
import { getMistake, saveSolvedQuestion, type SaveSolvedInput } from "../src/repo/mistakes";
import { applyRewrite, getRevision, revertRewrite } from "../src/repo/rewrite";
import { checkRewrite } from "../src/solve/rewrite";
import { createHarness } from "./support/node-sqlite";

const NOW = 1_700_000_000_000;
const OPTIONS = ["A. 0", "B. 1", "C. 2", "D. 4"];

describe("自洽校验", () => {
  it("步骤齐、答案非空：通过", () => {
    const result = checkRewrite({
      steps: [
        { idx: 1, content: "代入得 C 成立", why: "" },
        { idx: 2, content: "其余排除", why: "" },
      ],
      finalAnswer: "C",
      options: OPTIONS,
      choiceType: "single",
    });
    expect(result.ok).toBe(true);
  });

  it("只给结论没给过程：拒绝，且提示怎么补救", () => {
    const result = checkRewrite({ steps: [], finalAnswer: "C", options: OPTIONS, choiceType: "single" });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe("no_steps");
    expect(result.message).toContain("没给过程");
  });

  it("答案 C 但步骤推的是 A、C：判错位（0.8.0 的典型 bug）", () => {
    const result = checkRewrite({
      steps: [{ idx: 1, content: "由条件得 A、C 均成立", why: "" }],
      finalAnswer: "C",
      options: OPTIONS,
      choiceType: "single",
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe("mismatch");
  });

  it("答案里的字母不在选项内：拒绝", () => {
    const result = checkRewrite({
      steps: [{ idx: 1, content: "推得 E", why: "" }],
      finalAnswer: "E",
      options: OPTIONS,
      choiceType: "single",
    });
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toBe("unknown_option");
  });

  it("非选择题不参与字母校验", () => {
    const result = checkRewrite({
      steps: [{ idx: 1, content: "配方得顶点式", why: "" }],
      finalAnswer: "0",
      options: [],
      choiceType: "none",
    });
    expect(result.ok).toBe(true);
  });

  it("空答案拒绝", () => {
    const result = checkRewrite({ steps: [{ idx: 1, content: "x", why: "" }], finalAnswer: "   " });
    expect(result.ok).toBe(false);
  });

  it("通过时步骤重新编号，去掉空步骤", () => {
    const result = checkRewrite({
      steps: [
        { idx: 7, content: "第一步", why: "" },
        { idx: 9, content: "  ", why: "" },
        { idx: 11, content: "第二步", why: "" },
      ],
      finalAnswer: "0",
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.steps.map((step) => step.idx)).toEqual([1, 2]);
  });
});

function solveInput(): SaveSolvedInput {
  return {
    spec: {
      subject: "math",
      grade: "高一",
      title: "选择题",
      questionType: "选择",
      stem: "若 x^2=4，则 x 等于？",
      known: [],
      ask: "求 x",
      options: OPTIONS,
      choiceType: "single",
      hasFigure: false,
      stemLatex: "",
      handwriting: "none",
      handwritingText: "",
      progress: [],
      warnings: [],
    },
    solution: {
      steps: [
        { idx: 1, content: "解得 A", why: "" },
        { idx: 2, content: "检验", why: "" },
      ],
      finalAnswer: "A",
      method: "",
      check: "",
      confidence: 0.8,
    },
    report: {
      studentSteps: [{ idx: 1, content: "我算成 C", status: "wrong", alignTo: 1 }],
      firstError: {
        stepIdx: 1,
        subQuestion: null,
        type: "审题理解",
        explain: "漏了负根",
        correctThinking: "开方要带正负",
      },
      summaryCard: { title: "漏负根", bullets: [] },
    },
    kpoints: {
      kpoints: [{ id: "KP_MATH_0002", name: "二次函数", confidence: 0.9, usage: "" }],
      primaryId: "KP_MATH_0002",
      unmatched: false,
    },
    mode: "GUIDED",
    nowUtc: NOW,
    random: () => 0.5,
  };
}

async function seeded() {
  const { db, close } = createHarness();
  await ensureSchema(db);
  await db.run(
    "INSERT INTO knowledge_point (id, name, subject, chapter, orderIndex, definition, commonApproach, pitfallJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ["KP_MATH_0002", "二次函数", "math", "函数", 1, "", "", "[]"],
  );
  const ids = await saveSolvedQuestion(db, solveInput());
  return { db, close, ...ids };
}

describe("改写落库", () => {
  it("整段替换步骤与答案，旧版留一份", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      const result = await applyRewrite(db, {
        mistakeId,
        steps: [
          { idx: 1, content: "开方得 x=2 或 x=-2", why: "开方带正负" },
          { idx: 2, content: "因此只有 C 符合", why: "" },
        ],
        finalAnswer: "C",
        reason: "standard_answer",
        nowUtc: NOW + 1000,
      });
      expect(result.ok).toBe(true);

      const detail = await getMistake(db, mistakeId);
      expect(detail?.finalAnswer).toBe("C");
      expect(detail?.steps).toHaveLength(2);
      expect(detail?.steps[0]?.content).toContain("x=-2");

      const revision = await getRevision(db, mistakeId);
      expect(revision?.finalAnswer).toBe("A");
      expect(revision?.steps).toHaveLength(2);
    } finally {
      close();
    }
  });

  it("不动学生步骤、错因、知识点与掌握度（S-56）", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      const before = await getMistake(db, mistakeId);
      const masteryBefore = await db.all<{ score: number }>(
        "SELECT score FROM mastery WHERE kpointId = ?",
        ["KP_MATH_0002"],
      );

      await applyRewrite(db, {
        mistakeId,
        steps: [{ idx: 1, content: "新过程", why: "" }],
        finalAnswer: "C",
        reason: "standard_answer",
        nowUtc: NOW + 1000,
      });

      const after = await getMistake(db, mistakeId);
      expect(after?.errorType).toBe(before?.errorType);
      expect(after?.errorExplain).toBe(before?.errorExplain);
      expect(after?.studentSteps).toEqual(before?.studentSteps);
      expect(after?.kpointName).toBe(before?.kpointName);

      const masteryAfter = await db.all<{ score: number }>(
        "SELECT score FROM mastery WHERE kpointId = ?",
        ["KP_MATH_0002"],
      );
      expect(masteryAfter[0]?.score).toBe(masteryBefore[0]?.score);
    } finally {
      close();
    }
  });

  it("回退回到旧版，且旧版记录被清掉", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      await applyRewrite(db, {
        mistakeId,
        steps: [{ idx: 1, content: "新过程", why: "" }],
        finalAnswer: "C",
        reason: "standard_answer",
        nowUtc: NOW + 1000,
      });

      expect(await revertRewrite(db, mistakeId)).toEqual({ ok: true });
      const detail = await getMistake(db, mistakeId);
      expect(detail?.finalAnswer).toBe("A");
      expect(detail?.steps[0]?.content).toBe("解得 A");
      expect(await getRevision(db, mistakeId)).toBeNull();
    } finally {
      close();
    }
  });

  it("没有旧版时回退给出明确原因，不假装成功", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      expect(await revertRewrite(db, mistakeId)).toEqual({ ok: false, reason: "no_revision" });
    } finally {
      close();
    }
  });

  it("重复改写只留最近一版旧版", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      await applyRewrite(db, {
        mistakeId,
        steps: [{ idx: 1, content: "第一版新过程", why: "" }],
        finalAnswer: "B",
        reason: "standard_answer",
        nowUtc: NOW + 1000,
      });
      await applyRewrite(db, {
        mistakeId,
        steps: [{ idx: 1, content: "第二版新过程", why: "" }],
        finalAnswer: "C",
        reason: "standard_answer",
        nowUtc: NOW + 2000,
      });

      const revision = await getRevision(db, mistakeId);
      expect(revision?.finalAnswer).toBe("B");
    } finally {
      close();
    }
  });

  it("内容没变时不写库，也不产生旧版", async () => {
    const { db, close, mistakeId } = await seeded();
    try {
      const detail = await getMistake(db, mistakeId);
      if (!detail) throw new Error("准备数据失败");
      const result = await applyRewrite(db, {
        mistakeId,
        steps: detail.steps,
        finalAnswer: detail.finalAnswer,
        reason: "standard_answer",
        nowUtc: NOW + 1000,
      });
      expect(result).toEqual({ ok: false, reason: "unchanged" });
      expect(await getRevision(db, mistakeId)).toBeNull();
    } finally {
      close();
    }
  });

  it("题未入库时给 not_found（S-58）", async () => {
    const { db, close } = await seeded();
    try {
      const result = await applyRewrite(db, {
        mistakeId: "m_不存在",
        steps: [{ idx: 1, content: "x", why: "" }],
        finalAnswer: "C",
        reason: "standard_answer",
        nowUtc: NOW,
      });
      expect(result).toEqual({ ok: false, reason: "not_found" });
    } finally {
      close();
    }
  });
});
