/**
 * 单多选的三级策略与选项解析。
 *
 * 0.8.0 的做法是：题干字样猜单选多选，猜错就**把多字母答案截成一个**。
 * 这是最糟的一种错——学生看到的是"答案 C"，而模型其实给了 A、C。
 * 1.0 改成"永不静默丢字母"：判不准就问一句，问过一次就记住。
 */

/** 一道选择题的单个选项。 */
export interface OptionItem {
  /** A / B / C …；没有标号时按顺序补。 */
  label: string;
  text: string;
  selected: boolean;
}

export type ChoiceVerdict =
  | { kind: "not_choice" }
  | { kind: "ok"; letters: string[] }
  /** 题型是单选（或未定）但答案给了多个字母：不拦截，加一条确认条让学生确认。 */
  | { kind: "confirm_multi"; letters: string[] }
  /** 题型是多选但只给了一个字母：多半漏了，提示学生核对。 */
  | { kind: "under_count"; letters: string[] }
  /** 是选择题，但答案里找不到选项字母。 */
  | { kind: "unparsed" };

const LABELS = "ABCDEFGH";

/**
 * 从自由文本里抽选项字母。
 *
 * 只认"独立出现"的字母串：像 `pH` 这种字母混在单词里的不算，
 * 否则化学题的答案会莫名其妙多出一个 P。
 */
export function extractChoiceLetters(answer: string): string[] {
  const text = answer.trim();
  if (text === "") return [];

  const found: string[] = [];
  const pattern = new RegExp(`(?:^|[^A-Za-z])([${LABELS}]{1,8})(?![A-Za-z])`, "g");
  let match = pattern.exec(text);
  while (match !== null) {
    const run = match[1] ?? "";
    for (const ch of run) {
      if (!found.includes(ch)) found.push(ch);
    }
    match = pattern.exec(text);
  }
  return found.sort();
}

/** 解析选项原文：`A. 0`、`A、0`、`（A）0`、`A 0` 都认；没有标号就按顺序补。 */
export function parseOptions(options: readonly string[]): OptionItem[] {
  return options.map((raw, index) => {
    const text = raw.trim();
    const match = /^(?:（|[(（]?)([A-H])(?:）|\)|[.、．,，:：]|\s)\s*(.*)$/.exec(text);
    if (match) {
      return {
        label: match[1] ?? LABELS[index] ?? "",
        text: (match[2] ?? "").trim() || text,
        selected: false,
      };
    }
    return { label: LABELS[index] ?? "", text, selected: false };
  });
}

/** 给选中的选项打标，供页面逐行渲染时高亮。 */
export function markOptions(
  options: readonly string[],
  letters: readonly string[],
): OptionItem[] {
  return parseOptions(options).map((item) => ({
    ...item,
    selected: letters.includes(item.label),
  }));
}

/**
 * 判定一份选择题答案是否自洽。不修改答案，只给结论。
 *
 * 判据优先级（与提示词里的 `choiceType` 规则一致）：
 * 1. 不是选择题 → not_choice；
 * 2. 答案里没有字母 → unparsed；
 * 3. 声明多选但只给一个 → under_count（提示核对，不擅自补）；
 * 4. 声明单选 / 未定但给了多个 → confirm_multi（不拦截，问一句）；
 * 5. 其余 → ok。
 */
export function judgeChoice(
  choiceType: "single" | "multi" | "none" | "unknown",
  options: readonly string[],
  finalAnswer: string,
): ChoiceVerdict {
  if (choiceType === "none" || options.length === 0) return { kind: "not_choice" };

  const available = parseOptions(options).map((item) => item.label);
  const letters = extractChoiceLetters(finalAnswer).filter((letter) =>
    available.includes(letter),
  );

  if (letters.length === 0) return { kind: "unparsed" };
  if (choiceType === "multi" && letters.length < 2) return { kind: "under_count", letters };
  if (choiceType !== "multi" && letters.length > 1) {
    return { kind: "confirm_multi", letters };
  }
  return { kind: "ok", letters };
}
