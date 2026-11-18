/** 四阶段提示词。文本来自 assets/prompts，版本号参与缓存键。 */
export interface PromptSet {
  version: string;
  a: string;
  b: string;
  c: string;
  d: string;
}
