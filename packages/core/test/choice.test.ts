import { describe, expect, it } from "vitest";
import { extractChoiceLetters, judgeChoice, markOptions, parseOptions } from "../src/solve/choice";

const OPTIONS = ["A. 0", "B. 1", "C. 2", "D. 4"];

describe("选项字母提取", () => {
  it("认得常见的几种写法", () => {
    expect(extractChoiceLetters("A")).toEqual(["A"]);
    expect(extractChoiceLetters("A、C")).toEqual(["A", "C"]);
    expect(extractChoiceLetters("AC")).toEqual(["A", "C"]);
    expect(extractChoiceLetters("答案：C")).toEqual(["C"]);
    expect(extractChoiceLetters("故选AC。")).toEqual(["A", "C"]);
  });

  it("字母混在单词里不算：化学题的 pH 不该变成选项 P", () => {
    expect(extractChoiceLetters("pH=7")).toEqual([]);
  });

  it("没有字母时返回空，不瞎猜", () => {
    expect(extractChoiceLetters("无法确定")).toEqual([]);
    expect(extractChoiceLetters("")).toEqual([]);
  });
});

describe("选项解析", () => {
  it("四种标号写法都能拆出标号与正文", () => {
    expect(parseOptions(["A. 0", "B、1", "（C）2", "D 4"]).map((item) => item.label)).toEqual([
      "A",
      "B",
      "C",
      "D",
    ]);
    expect(parseOptions(["A. 0"])[0]?.text).toBe("0");
  });

  it("没有标号时按顺序补，正文保持原样", () => {
    expect(parseOptions(["铜丝", "蔗糖溶液"])).toEqual([
      { label: "A", text: "铜丝", selected: false },
      { label: "B", text: "蔗糖溶液", selected: false },
    ]);
  });

  it("高亮只标选中的项", () => {
    const marked = markOptions(OPTIONS, ["A", "C"]);
    expect(marked.map((item) => item.selected)).toEqual([true, false, true, false]);
  });
});

describe("单多选三级策略", () => {
  it("非选择题不参与判定", () => {
    expect(judgeChoice("none", [], "0")).toEqual({ kind: "not_choice" });
    expect(judgeChoice("single", [], "A")).toEqual({ kind: "not_choice" });
  });

  it("单选给一个字母：通过", () => {
    expect(judgeChoice("single", OPTIONS, "C")).toEqual({ kind: "ok", letters: ["C"] });
  });

  it("单选却给了多个字母：不拦截，要求确认", () => {
    expect(judgeChoice("single", OPTIONS, "A、C")).toEqual({
      kind: "confirm_multi",
      letters: ["A", "C"],
    });
  });

  it("题型未定但给了多个字母：同样只问一句", () => {
    expect(judgeChoice("unknown", OPTIONS, "AC").kind).toBe("confirm_multi");
  });

  it("声明多选却只给一个字母：提示核对，不擅自补", () => {
    expect(judgeChoice("multi", OPTIONS, "A")).toEqual({
      kind: "under_count",
      letters: ["A"],
    });
  });

  it("多选给了两个字母：通过", () => {
    expect(judgeChoice("multi", OPTIONS, "A、B")).toEqual({ kind: "ok", letters: ["A", "B"] });
  });

  it("选项字母不在选项里（例如答案写了 E）：当作没解析出答案", () => {
    expect(judgeChoice("single", OPTIONS, "E").kind).toBe("unparsed");
  });

  it("答案里夹着别的字母也能挑出有效项", () => {
    expect(judgeChoice("single", OPTIONS, "选 B")).toEqual({ kind: "ok", letters: ["B"] });
  });
});
