import type { LlmFailureKind } from "./types";

/**
 * 重试策略：1 秒、2 秒、4 秒，最多 3 次（与 0.8.0 一致）。
 *
 * 只有「换一次就可能好」的失败才重试：
 * - 网络抖动、限流、服务端 5xx：重试；
 * - Key 无效、余额不足、模型名错、地址错：重试一百次也一样，直接报；
 * - 取消：学生自己按的，不重试；
 * - 输出截断 / 解析失败：重试会把同样的 prompt 再算一遍，通常还是错，交给上层决定。
 */
const RETRYABLE: ReadonlySet<LlmFailureKind> = new Set<LlmFailureKind>([
  "network",
  "rate_limited",
  "server",
]);

export const MAX_ATTEMPTS = 3;

export function shouldRetry(kind: LlmFailureKind, attempt: number): boolean {
  if (attempt >= MAX_ATTEMPTS) return false;
  return RETRYABLE.has(kind);
}

/** 第 attempt 次失败后的等待（attempt 从 1 起）：1000 / 2000 / 4000。 */
export function backoffMs(attempt: number): number {
  const safe = Math.max(1, Math.min(attempt, MAX_ATTEMPTS));
  return 1000 * 2 ** (safe - 1);
}
