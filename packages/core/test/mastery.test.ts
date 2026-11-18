import { describe, expect, it } from "vitest";
import {
  applyMasteryEvent,
  bandOf,
  clampScore,
  heatOf,
  INITIAL_MASTERY,
  masteredRatio,
} from "../src/mastery/algorithm";

describe("掌握度更新", () => {
  it("做对提升、仍不懂下降、错题下降但更温和", () => {
    const base = 0.5;
    expect(applyMasteryEvent(base, "correct")).toBeGreaterThan(base);
    expect(applyMasteryEvent(base, "wrong")).toBeLessThan(base);
    expect(applyMasteryEvent(base, "mistake")).toBeLessThan(base);
    // 做错比"新错题"影响大：前者是学生自己承认没懂
    expect(applyMasteryEvent(base, "wrong")).toBeLessThan(applyMasteryEvent(base, "mistake"));
  });

  it("越接近两端，单次变化越小", () => {
    const highGain = applyMasteryEvent(0.9, "correct") - 0.9;
    const lowGain = applyMasteryEvent(0.2, "correct") - 0.2;
    expect(highGain).toBeLessThan(lowGain);

    const highDrop = 0.9 - applyMasteryEvent(0.9, "wrong");
    const lowDrop = 0.2 - applyMasteryEvent(0.2, "wrong");
    expect(highDrop).toBeGreaterThan(lowDrop);
  });

  it("永远落在 0 到 1 之间", () => {
    let score = INITIAL_MASTERY;
    for (let i = 0; i < 40; i += 1) score = applyMasteryEvent(score, "correct");
    expect(score).toBeLessThanOrEqual(1);
    for (let i = 0; i < 40; i += 1) score = applyMasteryEvent(score, "wrong");
    expect(score).toBeGreaterThanOrEqual(0);
  });

  it("非法输入回落到初始值，不产生 NaN", () => {
    expect(clampScore(Number.NaN)).toBe(INITIAL_MASTERY);
    expect(applyMasteryEvent(Number.NaN, "correct")).toBeGreaterThan(INITIAL_MASTERY);
  });

  it("反复做对会逼近 1，但不会越过", () => {
    let score = 0.5;
    for (let i = 0; i < 20; i += 1) score = applyMasteryEvent(score, "correct");
    expect(score).toBeGreaterThan(0.99);
    expect(score).toBeLessThanOrEqual(1);
  });
});

describe("热力与区间", () => {
  it("红 <40，黄 40 到 70，绿 >70", () => {
    expect(heatOf(0.39)).toBe("red");
    expect(heatOf(0.4)).toBe("yellow");
    expect(heatOf(0.7)).toBe("yellow");
    expect(heatOf(0.71)).toBe("green");
  });

  it("区间与热力一致，避免两套阈值", () => {
    expect(bandOf(0.2)).toBe("weak");
    expect(bandOf(0.5)).toBe("mid");
    expect(bandOf(0.9)).toBe("strong");
  });

  it("已掌握比例按 0.6 线算", () => {
    expect(masteredRatio([0.7, 0.5, 0.9, 0.6])).toBe(0.75);
    expect(masteredRatio([])).toBe(0);
  });
});
