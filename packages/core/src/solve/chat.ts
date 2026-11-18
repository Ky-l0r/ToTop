/**
 * 对话上下文。
 *
 * 陪伴页是"嘴上问一句"，不是长对话。学生问五六轮就该拿到答案了，
 * 所以这里用**滑动窗口**，不做摘要：留最近 8 条，超出的直接丢。
 *
 * 为什么不做摘要：摘要要再调一次模型，学生为一个随口的问题多付一次钱；
 * 而且摘要会把之前算错的步骤"复述"进去，把错误固化成上下文。
 * 真需要回溯时，学生可以重问——重问的成本远低于被错误摘要带偏。
 */

/** 一问一答。系统提示词不在这里——它由调用方拼在最前面。 */
export interface ChatTurn {
  role: "user" | "assistant";
  content: string;
}

/** 默认窗口：4 轮问答。再多就要考虑是不是该去拍题页把题发出来了。 */
export const CHAT_WINDOW = 8;

/** 截到最近 `maxMessages` 条。从用户消息起头，避免出现"助手连着说两句"。 */
export function trimChat(messages: readonly ChatTurn[], maxMessages = CHAT_WINDOW): ChatTurn[] {
  if (messages.length <= maxMessages) return [...messages];

  let start = messages.length - maxMessages;
  // 窗口起点若落在助手消息上，往前挪一条：上下文以学生的话开头更自然，
  // 也避免模型接到一个没有对应提问的回答。
  if (messages[start]?.role === "assistant" && start > 0) {
    start -= 1;
  }
  return messages.slice(start);
}

/** 送进模型的消息序列：系统提示词 + 裁剪后的历史 + 这一问。 */
export function buildChatMessages(
  system: string,
  history: readonly ChatTurn[],
  question: string,
  maxMessages = CHAT_WINDOW,
): Array<{ role: "system" | "user" | "assistant"; content: string }> {
  return [
    { role: "system", content: system },
    ...trimChat(history, maxMessages),
    { role: "user", content: question },
  ];
}

/**
 * 判空：只有空白字符的问题不发。
 * 0.8.0 会把空格发出去，模型回一句"请问你想问什么"，学生以为是 AI 犯傻。
 */
export function isBlankQuestion(text: string): boolean {
  return text.trim() === "";
}
