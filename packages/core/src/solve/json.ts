/**
 * 从模型输出里捞 JSON。
 *
 * 模型经常干三件事：包一层 ```json 围栏、前后加一句「好的，这是结果」、
 * 在被截断时少几个右括号。前两件必须容忍，第三件必须识别出来并明确报错，
 * 不能悄悄补括号——补出来的对象是半截的，写进库就是脏数据。
 */
export interface JsonExtraction {
  ok: boolean;
  value?: unknown;
  /** 失败原因，直接给用户看的一句话。 */
  reason?: string;
}

export function extractJson(raw: string): JsonExtraction {
  const text = stripFence(raw).trim();
  if (text === "") return { ok: false, reason: "模型没有返回内容" };

  const start = text.indexOf("{");
  if (start < 0) return { ok: false, reason: "模型返回的不是 JSON 对象" };

  const end = findBalancedEnd(text, start);
  if (end < 0) {
    return { ok: false, reason: "模型的输出被截断了（JSON 没有闭合）" };
  }

  const slice = text.slice(start, end + 1);
  try {
    return { ok: true, value: JSON.parse(slice) as unknown };
  } catch {
    return { ok: false, reason: "模型的输出不是合法的 JSON" };
  }
}

/** 去掉 ```json … ``` 围栏；没有围栏时原样返回。 */
export function stripFence(raw: string): string {
  const fence = /```(?:json|JSON)?\s*([\s\S]*?)```/.exec(raw);
  return fence?.[1] ?? raw;
}

/**
 * 从 start 位置的 `{` 找配对的 `}`。
 * 必须认字符串里的花括号与转义引号，否则题干里的 `{x|x>0}` 会把计数带偏。
 */
function findBalancedEnd(text: string, start: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let i = start; i < text.length; i += 1) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === "\\") {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    if (ch === "{") depth += 1;
    if (ch === "}") {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}
