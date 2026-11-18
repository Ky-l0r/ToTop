import { describe, expect, it } from "vitest";
import { CORE_VERSION } from "./index";

/**
 * 骨架冒烟测试。
 *
 * T1 的领域层只有一个版本号，这里不是为它写测试，而是**证明测试链路是通的**：
 * vitest 能跑 TS、能解析本包、能在 Node 里执行。
 * 真正的算法用例（掌握度、题型判定、改写自洽校验）从 T3 与 T6 开始往这里加。
 */
describe("领域层骨架", () => {
  it("导出版本号，且格式为三段式", () => {
    expect(CORE_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });
});
