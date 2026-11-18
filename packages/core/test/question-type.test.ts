import { describe, expect, it } from "vitest";
import {
  labelOf,
  questionTypesFor,
  resolveQuestionType,
  skeletonFor,
  skeletonInstruction,
  SKELETONS,
} from "../src/solve/question-type";

describe("学科 × 题型", () => {
  it("每个学科都有允许的题型，且至少一档", () => {
    for (const subject of ["chinese", "math", "english", "physics", "chemistry"] as const) {
      expect(questionTypesFor(subject).length).toBeGreaterThan(0);
    }
  });

  it("合法取值原样收下，不标记为猜测", () => {
    expect(resolveQuestionType("math", "填空")).toEqual({ type: "填空", guessed: false });
    expect(resolveQuestionType("english", "七选五")).toEqual({ type: "七选五", guessed: false });
  });

  it("跨学科的题型不认，回落到本科目最常见的一档并标记为猜测", () => {
    const resolved = resolveQuestionType("math", "现代文阅读");
    expect(resolved.guessed).toBe(true);
    expect(resolved.type).toBe("大题");
  });

  it("单题型科目（物理）无论模型写什么都只有一档", () => {
    expect(resolveQuestionType("physics", "选择题")).toEqual({ type: "全题型", guessed: true });
    expect(resolveQuestionType("physics", "").type).toBe("全题型");
  });

  it("空值也不炸，给兜底并标记", () => {
    const resolved = resolveQuestionType("chinese", "  ");
    expect(resolved.guessed).toBe(true);
    expect(resolved.type).toBe("现代文阅读");
  });
});

describe("骨架", () => {
  it("精确组合能取到对应骨架", () => {
    expect(skeletonFor("math", "大题")).toContain("分步给分点");
    expect(skeletonFor("english", "完形")).toContain("上下文复现");
  });

  it("组合不存在时退到全题型，再退到通用，不返回空", () => {
    expect(skeletonFor("physics", "某个没见过的题型")).toBe(SKELETONS["物理/全题型"]);
    expect(skeletonFor("physics", "某个没见过的题型")).not.toBe("");
  });

  it("每个学科都有可用的骨架，没有漏配的科目", () => {
    for (const subject of [
      "chinese",
      "math",
      "english",
      "physics",
      "chemistry",
      "biology",
      "technology",
      "politics",
      "history",
      "geography",
      "general",
    ] as const) {
      const type = questionTypesFor(subject)[0] ?? "全题型";
      expect(skeletonFor(subject, type)).not.toBe("");
    }
  });

  it("注入用的说明包含学科、题型与必须写到的环节", () => {
    const text = skeletonInstruction("chemistry", "全题型");
    expect(text).toContain("学科：化学；题型：全题型");
    expect(text).toContain("方程式配平");
    expect(text).toContain("不要把其他学科的结构套过来");
  });

  it("学科中文名覆盖全部取值", () => {
    expect(labelOf("politics")).toBe("政治");
    expect(labelOf("general")).toBe("通用");
  });
});
