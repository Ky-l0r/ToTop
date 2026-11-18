import { describe, expect, it } from "vitest";
import { CORE_VERSION } from "../src/index";

/** 领域层能被打包/解析的最基础证据：入口可导入且导出了版本号。 */
describe("领域层入口", () => {
  it("导出版本号，且为三段式", () => {
    expect(CORE_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
