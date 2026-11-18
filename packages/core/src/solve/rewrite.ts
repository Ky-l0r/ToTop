import { extractChoiceLetters } from "./choice";
import type { SolutionStep } from "./contracts";

/**
 * 改写内核：把一份新的解答过程写回这道题。
 *
 * 三个入口共用（导入标准答案、解答卡"改错"、对话订正），规则一致：
 * **整段替换**——步骤、每步依据、最终答案一起换。
 *
 * 0.8.0 只替换了 `finalAnswer`，于是解答卡上会出现"答案是 C，往下读步骤推的是 A、C"
 * 这种错位。学生复习时看的就是这套自相矛盾的东西，而且没有任何提示告诉他哪里不对。
 */

export interface RewriteInput {
  steps: SolutionStep[];
  finalAnswer: string;
  /** 选择题的选项，用于交叉校验；非选择题传空数组。 */
  options?: readonly string[];
  choiceType?: "single" | "multi" | "none" | "unknown";
}

export type RewriteRejection =
  /** 只给了一句"答案是 C"，没给过程——不许用旧步骤配新答案。 */
  | "no_steps"
  /** 答案是空的。 */
  | "no_answer"
  /** 步骤里推出的字母与最终答案对不上。 */
  | "mismatch"
  /** 答案里的字母不在选项里。 */
  | "unknown_option";

export type RewriteCheck =
  | { ok: true; steps: SolutionStep[]; finalAnswer: string }
  | { ok: false; reason: RewriteRejection; message: string };

const MESSAGES: Record<RewriteRejection, string> = {
  no_steps: "这次只给了结论，没给过程。让它重讲一遍，或者换一份标准答案。",
  no_answer: "这份答案里没有结论，没法改写。",
  mismatch: "步骤推出来的结论与最终答案不一致，这次先不改。",
  unknown_option: "答案里的选项不在题目选项里，这次先不改。",
};

/**
 * 自洽校验。
 *
 * 判断"步骤推出来的结论等于最终答案"本来该由人读一遍，这里用两条可机械判断的规则兜底：
 * 1. 选择题：最终答案的每个字母，都必须在这份步骤的文本里出现过——
 *    否则就是"答案 C、步骤推 A、C"那类错位；
 * 2. 答案里的字母必须都在选项内。
 *
 * 它会漏判（步骤里出现 C 却不等于结论），但不会误放 0.8.0 那种最典型的错位。
 */
export function checkRewrite(input: RewriteInput): RewriteCheck {
  const finalAnswer = input.finalAnswer.trim();
  const steps = input.steps
    .filter((step) => step.content.trim() !== "")
    .map((step, index) => ({ ...step, idx: index + 1 }));

  if (finalAnswer === "") {
    return { ok: false, reason: "no_answer", message: MESSAGES.no_answer };
  }
  if (steps.length === 0) {
    return { ok: false, reason: "no_steps", message: MESSAGES.no_steps };
  }

  const options = input.options ?? [];
  const isChoice = input.choiceType !== "none" && options.length > 0;
  if (!isChoice) return { ok: true, steps, finalAnswer };

  const answerLetters = extractChoiceLetters(finalAnswer);
  if (answerLetters.length === 0) {
    // 选择题却写不出字母：当作只给了结论，不改
    return { ok: false, reason: "unknown_option", message: MESSAGES.unknown_option };
  }

  const available = options.map((option) => firstLetter(option)).filter((item) => item !== "");
  if (available.length > 0 && answerLetters.some((letter) => !available.includes(letter))) {
    return { ok: false, reason: "unknown_option", message: MESSAGES.unknown_option };
  }

  const allLetters = new Set(
    extractChoiceLetters(steps.map((step) => `${step.content} ${step.why}`).join(" ")),
  );
  // 答案必须在这份过程里出现过：否则就是"过程推 A，答案写 C"这种接不上的情况
  if (answerLetters.some((letter) => !allLetters.has(letter))) {
    return { ok: false, reason: "mismatch", message: MESSAGES.mismatch };
  }

  // 最后一步是结论步：它不该推出比答案更多的选项。
  // 0.8.0 的错位正是"答案是 C，步骤里却写着 A、C 都成立"。
  const last = steps[steps.length - 1];
  const lastLetters = extractChoiceLetters(`${last?.content ?? ""} ${last?.why ?? ""}`);
  if (lastLetters.some((letter) => !answerLetters.includes(letter))) {
    return { ok: false, reason: "mismatch", message: MESSAGES.mismatch };
  }

  return { ok: true, steps, finalAnswer };
}

/** 选项文本开头的字母，`A. 0` / `A、0` / `（A）0` 都认。 */
function firstLetter(option: string): string {
  const match = /^[（(]?([A-H])[）)]?[.、．,，:：\s]?/.exec(option.trim());
  return match?.[1] ?? "";
}
