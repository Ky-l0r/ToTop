import type { PromptSet } from "@totop/core";
import versionInfo from "../assets/prompts/prompt_version.json";
import stageA from "../assets/prompts/stage_a_identify.md?raw";
import stageB from "../assets/prompts/stage_b_solve.md?raw";
import stageC from "../assets/prompts/stage_c_locate_error.md?raw";
import stageD from "../assets/prompts/stage_d_kpoints.md?raw";

/**
 * 四阶段提示词。
 *
 * 以 `?raw` 打进包，不转成字符串常量：提示词是数据，改文案不该动构建配置，
 * 也不该在代码里出现一份巨型模板字符串。
 *
 * 版本号来自 prompt_version.json，参与缓存键。改任一阶段文本都必须同步升版本，
 * 否则同题同模型的请求会命中旧缓存，学生看到的是上一次的解析结果。
 */
export const PROMPTS: PromptSet = {
  version: versionInfo.promptVersion,
  a: stageA,
  b: stageB,
  c: stageC,
  d: stageD,
};
