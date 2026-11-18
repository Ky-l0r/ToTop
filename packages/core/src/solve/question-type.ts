import type { Subject } from "./contracts";

/**
 * 学科 × 题型，以及每种题型的解析骨架。
 *
 * 为什么要分：一道数学大题和一道英语完形，用同一套"先给结论再给步骤"的模板，
 * 前者会漏给分点，后者会写成一片散句。0.8.0 把所有科目都当数学处理，
 * 语文题给"步骤 1 2 3"、英语作文给"公式"，学生一眼就知道这东西不是给他准备的。
 *
 * 取值是受控枚举，AI 不许自造：题型判错会让后面的数量校验（单选只能一个字母）
 * 与版式（选项要不要逐行）全部走偏。
 */

export const QUESTION_TYPES: Readonly<Record<Subject, readonly string[]>> = {
  chinese: ["现代文阅读", "文言文", "作文"],
  math: ["选择", "填空", "大题"],
  english: ["阅读", "七选五", "完形", "语法填空", "作文", "读后续写"],
  physics: ["全题型"],
  chemistry: ["全题型"],
  biology: ["全题型"],
  technology: ["全题型"],
  politics: ["全题型"],
  history: ["全题型"],
  geography: ["全题型"],
  general: ["全题型"],
};

/** 判不出来时的兜底：取该科最常见的题型，并如实标记"这是猜的"。 */
const FALLBACK_TYPE: Readonly<Record<Subject, string>> = {
  chinese: "现代文阅读",
  math: "大题",
  english: "阅读",
  physics: "全题型",
  chemistry: "全题型",
  biology: "全题型",
  technology: "全题型",
  politics: "全题型",
  history: "全题型",
  geography: "全题型",
  general: "全题型",
};

/** 骨架：解析必须写到的环节，不是排版模板。 */
export const SKELETONS: Readonly<Record<string, string>> = {
  "语文/现代文阅读": "定位原文 → 手法判断 → 作用分析 → 落到文本依据",
  "语文/文言文": "断句依据 → 实词虚词 → 翻译逐句 → 文意概括",
  "语文/作文": "审题立意 → 结构提纲 → 素材适配 → 语言升格建议",
  "数学/选择": "逐项验证或排除，说明每个选项为什么对、为什么错",
  "数学/填空": "结果 + 关键一步，不写满篇推导",
  "数学/大题": "分步给分点，每步写依据（定理名、公式名）",
  "英语/阅读": "定位句 → 同义替换 → 排除干扰项",
  "英语/七选五": "位置逻辑 → 代词指代 → 衔接词",
  "英语/完形": "上下文复现 → 固定搭配 → 语义场",
  "英语/语法填空": "考点判定 → 变形过程 → 拼写检查",
  "英语/作文": "要点覆盖 → 句式升级 → 连接词",
  "英语/读后续写": "情节走向 → 与前文衔接 → 描写层次",
  "物理/全题型": "受力或过程分析 → 公式 → 代入 → 单位与有效数字",
  "化学/全题型": "方程式配平 → 反应条件 → 现象 → 计算",
  "生物/全题型": "概念 → 过程 → 结论",
  "技术/全题型": "结构或算法分析 → 步骤 → 结论",
  "政治/全题型": "材料信息 → 原理 → 结合材料表述",
  "历史/全题型": "材料信息 → 史实 → 结合材料表述",
  "地理/全题型": "材料信息 → 原理 → 结合材料表述",
  "通用/全题型": "先判断题目在问什么，再按该学科常用方式作答",
};

export interface ResolvedQuestionType {
  type: string;
  /** 是否因为无法识别而用了兜底值。兜底值要写进 warnings，不许悄悄替换。 */
  guessed: boolean;
}

/** 该科目允许的题型列表。 */
export function questionTypesFor(subject: Subject): readonly string[] {
  return QUESTION_TYPES[subject] ?? QUESTION_TYPES.general;
}

/**
 * 把 AI 给出的题型收敛到受控枚举。
 *
 * 判不出来时**不硬猜一个精确题型**，而是落到该科最常见的那一档并标记 guessed，
 * 由调用方写进 warnings。硬猜的代价是版式与答案数量校验一起错。
 */
export function resolveQuestionType(subject: Subject, raw: string): ResolvedQuestionType {
  const allowed = questionTypesFor(subject);
  const value = raw.trim();
  if (allowed.includes(value)) return { type: value, guessed: false };

  // 单题型的科目（物理等）无论 AI 写什么，都只有一档
  if (allowed.length === 1) return { type: allowed[0] ?? "全题型", guessed: value !== allowed[0] };

  return { type: FALLBACK_TYPE[subject] ?? "全题型", guessed: true };
}

/** 取骨架。找不到精确组合时退到该科的全题型骨架，再退到通用。 */
export function skeletonFor(subject: Subject, type: string): string {
  return (
    SKELETONS[`${labelOf(subject)}/${type}`] ??
    SKELETONS[`${labelOf(subject)}/全题型`] ??
    SKELETONS["通用/全题型"] ??
    ""
  );
}

/**
 * 注入到阶段 B 系统提示词末尾的骨架要求。
 *
 * 为什么不改 stage_b 正文：那是 195 行的通用契约，按学科分叉会把一份文件写成十份，
 * 每次调措辞都要改十处。骨架是"这一题额外要注意什么"，append 在系统提示词末尾即可。
 */
export function skeletonInstruction(subject: Subject, type: string): string {
  const skeleton = skeletonFor(subject, type);
  return [
    "【本题骨架】",
    `学科：${labelOf(subject)}；题型：${type}。`,
    `解答必须写到这些环节：${skeleton}。`,
    "按该学科的规范表述作答，不要把其他学科的结构套过来。",
  ].join("\n");
}

/** 学科的中文名，用于骨架键与提示词。 */
export function labelOf(subject: Subject): string {
  switch (subject) {
    case "chinese":
      return "语文";
    case "math":
      return "数学";
    case "english":
      return "英语";
    case "physics":
      return "物理";
    case "chemistry":
      return "化学";
    case "biology":
      return "生物";
    case "technology":
      return "技术";
    case "politics":
      return "政治";
    case "history":
      return "历史";
    case "geography":
      return "地理";
    default:
      return "通用";
  }
}
