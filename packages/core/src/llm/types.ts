/** token 用量（OpenAI 兼容的 usage 字段）。 */
export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
}

export const EMPTY_USAGE: TokenUsage = { inputTokens: 0, outputTokens: 0 };

export function addUsage(a: TokenUsage, b: TokenUsage): TokenUsage {
  return {
    inputTokens: a.inputTokens + b.inputTokens,
    outputTokens: a.outputTokens + b.outputTokens,
  };
}

/** 调用失败的分档。UI 必须按档给不同的话，不许笼统报「请求失败」。 */
export type LlmFailureKind =
  | "unauthorized"
  | "insufficient_quota"
  | "rate_limited"
  | "model_not_found"
  | "bad_base_url"
  | "network"
  | "server"
  | "output_truncated"
  | "output_empty"
  | "parse_failed"
  | "aborted";

export interface LlmFailure {
  kind: LlmFailureKind;
  /** 给用户看的一句话，不含 Key、不含题干。 */
  message: string;
  /** 原始 HTTP 状态码，仅用于日志排查。 */
  status?: number;
}

/** 流式增量块。 */
export type LlmChunk =
  | { kind: "delta"; text: string }
  | { kind: "reasoning"; text: string }
  | { kind: "finished"; usage: TokenUsage; finishReason: string }
  | { kind: "failed"; failure: LlmFailure };

/** 一条对话消息。图片以 data URL 或本地路径给出，由驱动决定怎么塞进请求体。 */
export interface ChatMessage {
  role: "system" | "user";
  content: string;
  images?: string[];
}

export interface LlmRequest {
  messages: ChatMessage[];
  model: string;
  temperature?: number;
  maxTokens?: number;
  /** 要求厂商返回严格 JSON（OpenAI 的 response_format）。 */
  jsonMode?: boolean;
}

/**
 * 传输层接口。
 *
 * 领域层只认这个接口：编排、解析、重试全在上面，驱动只负责「把字节变成 chunk」。
 * 端上由 uni.request / 原生插件实现，测试里用脚本化的假实现。
 */
export interface LlmTransport {
  stream(request: LlmRequest, signal: AbortSignal): AsyncIterable<LlmChunk>;
}
