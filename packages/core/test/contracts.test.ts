import { describe, expect, it } from "vitest";
import {
  fallbackTitle,
  KP_ID_PATTERN,
  parseErrorReport,
  parseKpointMatches,
  parseQuestionSpec,
  parseStandardSolution,
} from "../src/solve/contracts";

const validA = JSON.stringify({
  subject: "math",
  grade: "高一",
  title: "二次函数在闭区间的最值",
  stem: "已知 f(x)=x^2-2x+1，求 f(x) 在 [0,3] 上的最小值。",
  known: ["f(x)=x^2-2x+1"],
  ask: "求最小值",
  options: ["A. 0", "B. 1"],
  choiceType: "single",
  hasFigure: false,
  stemLatex: "$f(x)=x^2-2x+1$",
  handwriting: "partial",
  handwritingText: "配方得 (x-1)^2",
  progress: [{ no: 1, state: "done" }],
  warnings: [],
});

describe("阶段 A 契约", () => {
  it("正常输出逐字段读出来", () => {
    const parsed = parseQuestionSpec(validA);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.subject).toBe("math");
    expect(parsed.value.title).toBe("二次函数在闭区间的最值");
    expect(parsed.value.choiceType).toBe("single");
    expect(parsed.value.progress).toEqual([{ no: 1, state: "done" }]);
  });

  it("标题缺失时用题干前 12 字兜底，并记一笔", () => {
    const raw = JSON.stringify({ ...JSON.parse(validA), title: "" });
    const parsed = parseQuestionSpec(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.title.length).toBeLessThanOrEqual(13);
    expect(parsed.notes.join()).toContain("title");
  });

  it("学科乱填降级为 general，不整条失败", () => {
    const raw = JSON.stringify({ ...JSON.parse(validA), subject: "化学竞赛" });
    const parsed = parseQuestionSpec(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.subject).toBe("general");
    expect(parsed.notes.join()).toContain("general");
  });

  it("题干为空直接失败：这种回答没法用", () => {
    const raw = JSON.stringify({ ...JSON.parse(validA), stem: "  " });
    expect(parseQuestionSpec(raw).ok).toBe(false);
  });
});

describe("阶段 B 契约", () => {
  it("步骤与答案读出来，缺省字段给安全的空值", () => {
    const raw = JSON.stringify({
      steps: [
        { idx: 1, content: "配方", why: "化为顶点式" },
        { idx: 2, content: "代入端点", why: "比较" },
      ],
      finalAnswer: "0",
      method: "配方法",
    });
    const parsed = parseStandardSolution(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.steps).toHaveLength(2);
    expect(parsed.value.check).toBe("");
    expect(parsed.value.confidence).toBe(0);
  });

  it("步骤全空时失败", () => {
    const parsed = parseStandardSolution(JSON.stringify({ steps: [], finalAnswer: "0" }));
    expect(parsed.ok).toBe(false);
  });

  it("只给一步时保留但记一笔", () => {
    const parsed = parseStandardSolution(
      JSON.stringify({ steps: [{ idx: 1, content: "一步到位" }], finalAnswer: "1" }),
    );
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.notes.join()).toContain("1 步");
  });
});

describe("阶段 C 契约", () => {
  const base = {
    studentSteps: [{ idx: 1, content: "配方", status: "correct", alignTo: 1 }],
    firstError: {
      stepIdx: 2,
      subQuestion: null,
      type: "计算失误",
      explain: "符号写反",
      correctThinking: "先定符号再代入",
    },
    summaryCard: { title: "配方要看符号", bullets: ["先配方", "再代端点"] },
  };

  it("错因七类之内正常收下", () => {
    const parsed = parseErrorReport(JSON.stringify(base));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.firstError?.type).toBe("计算失误");
    expect(parsed.value.summaryCard.bullets).toHaveLength(2);
  });

  it("错因写了七类之外的词：降级为不标错因，但解答仍然可用", () => {
    const raw = JSON.stringify({
      ...base,
      firstError: { ...base.firstError, type: "粗心大意" },
    });
    const parsed = parseErrorReport(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.firstError).toBeNull();
    expect(parsed.notes.join()).toContain("粗心大意");
  });

  it("firstError 为 null 是合法输出（只知道答案对错时）", () => {
    const parsed = parseErrorReport(JSON.stringify({ ...base, firstError: null }));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.firstError).toBeNull();
  });
});

describe("阶段 D 契约", () => {
  it("不合法的知识点 ID 被丢弃并记一笔", () => {
    const raw = JSON.stringify({
      kpoints: [
        { id: "KP_MATH_0001", name: "二次函数", confidence: 0.9, usage: "配方" },
        { id: "自己编的", name: "野节点", confidence: 0.8, usage: "x" },
      ],
      primaryId: "KP_MATH_0001",
      unmatched: false,
    });
    const parsed = parseKpointMatches(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.kpoints).toHaveLength(1);
    expect(parsed.notes.join()).toContain("ID 不合法");
  });

  it("primaryId 不在列表里时置空", () => {
    const raw = JSON.stringify({
      kpoints: [{ id: "KP_MATH_0001", name: "二次函数", confidence: 0.9, usage: "配方" }],
      primaryId: "KP_MATH_9999",
      unmatched: false,
    });
    const parsed = parseKpointMatches(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.primaryId).toBeNull();
  });

  it("最多收三个", () => {
    const kpoints = [1, 2, 3, 4, 5].map((i) => ({
      id: `KP_MATH_000${i}`,
      name: `点${i}`,
      confidence: 0.5,
      usage: "u",
    }));
    const parsed = parseKpointMatches(JSON.stringify({ kpoints, primaryId: null, unmatched: false }));
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.kpoints).toHaveLength(3);
  });
});

describe("公共工具", () => {
  it("知识点 ID 正则是受控的", () => {
    expect(KP_ID_PATTERN.test("KP_MATH_0312")).toBe(true);
    expect(KP_ID_PATTERN.test("KP_MATH_312")).toBe(false);
    expect(KP_ID_PATTERN.test("kp_math_0312")).toBe(false);
  });

  it("标题兜底去空白、超长截断", () => {
    expect(fallbackTitle("  已知  函数  ")).toBe("已知 函数");
    expect(fallbackTitle("一二三四五六七八九十十一十二十三")).toBe("一二三四五六七八九十十一…");
  });
});
