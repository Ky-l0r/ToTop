import { describe, expect, it } from "vitest";
import {
  IMAGE_PROFILE_LEAN,
  IMAGE_PROFILE_STANDARD,
  pickProfile,
  planResize,
} from "../src/image/plan";

describe("图片缩放规划", () => {
  it("横图按长边收口，比例不变", () => {
    expect(planResize({ width: 4000, height: 3000 }, 1600)).toEqual({
      width: 1600,
      height: 1200,
      scale: 0.4,
      resized: true,
    });
  });

  it("竖图按长边收口，不会压成一条线", () => {
    const plan = planResize({ width: 1080, height: 5000 }, 1600);
    expect(plan.height).toBe(1600);
    expect(plan.width).toBe(346);
    expect(plan.resized).toBe(true);
  });

  it("本来就不超长边时不动它，避免无谓的有损重编码", () => {
    expect(planResize({ width: 1200, height: 900 }, 1600)).toEqual({
      width: 1200,
      height: 900,
      scale: 1,
      resized: false,
    });
  });

  it("极端细长的图两边都不小于 1px", () => {
    const plan = planResize({ width: 1, height: 9000 }, 1600);
    expect(plan.width).toBe(1);
    expect(plan.height).toBe(1600);
  });

  it("非法尺寸不产生 0 或负数", () => {
    const plan = planResize({ width: 0, height: 0 }, 1600);
    expect(plan.width).toBeGreaterThanOrEqual(1);
    expect(plan.height).toBeGreaterThanOrEqual(1);
  });

  it("低内存机降档，普通机用标准档", () => {
    expect(pickProfile(2 * 1024 ** 3)).toBe(IMAGE_PROFILE_LEAN);
    expect(pickProfile(6 * 1024 ** 3)).toBe(IMAGE_PROFILE_STANDARD);
    // 读不到内存信息时按标准档，不用降档惩罚所有人
    expect(pickProfile(0)).toBe(IMAGE_PROFILE_STANDARD);
  });

  it("标准档与省内存档的边与质量符合指南", () => {
    expect(IMAGE_PROFILE_STANDARD).toEqual({ maxEdge: 1600, quality: 85 });
    expect(IMAGE_PROFILE_LEAN).toEqual({ maxEdge: 1280, quality: 80 });
  });
});
