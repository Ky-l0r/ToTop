import type { SseEvent } from "./sse";
import type { ChatMessage, LlmChunk, LlmRequest, TokenUsage } from "./types";

/**
 * OpenAI 兼容协议的接线细节。
 *
 * 放在领域层而不是驱动里：请求体怎么拼、流里的字段叫什么，
 * 都是"协议"知识，跟用 uni.request 还是 fetch 无关。
 * 驱动只负责发字节与收字节。
 */

export const DONE_MARKER = "[DONE]";

/**
 * 拼请求体。
 *
 * `stream` 由传输层决定：能收 chunk 就开流，收不到（App 端没有 chunked 通道）就关掉，
 * 一次性拿整段正文。**开着流却按非流式读**只会拿到一串 SSE 文本，
 * 所以这两个开关必须由同一个地方控制。
 */
export function buildChatBody(
  request: LlmRequest,
  options: { stream?: boolean } = {},
): Record<string, unknown> {
  const stream = options.stream ?? true;
  const body: Record<string, unknown> = {
    model: request.model,
    stream,
    messages: request.messages.map(toWireMessage),
  };
  // include_usage 只在流式下有意义，非流式的用量本来就在响应体里。
  if (stream) body.stream_options = { include_usage: true };
  if (request.temperature !== undefined) body.temperature = request.temperature;
  if (request.maxTokens !== undefined) body.max_tokens = request.maxTokens;
  if (request.jsonMode) body.response_format = { type: "json_object" };
  return body;
}

/** 非流式响应的解析结果。 */
export interface CompletionResult {
  text: string;
  usage: TokenUsage;
  finishReason: string;
}

/**
 * 解析非流式的 `/chat/completions` 响应体。
 *
 * 与流式走同一套契约（text + usage + finish_reason），
 * 这样编排层不必知道自己拿到的是一段流还是一整个响应。
 */
export function parseCompletion(raw: string): CompletionResult | { error: string } {
  let payload: {
    choices?: Array<{ message?: { content?: string | null }; finish_reason?: string | null }>;
    usage?: { prompt_tokens?: number; completion_tokens?: number } | null;
  };
  try {
    payload = JSON.parse(raw) as typeof payload;
  } catch {
    return { error: "模型返回的不是 JSON" };
  }
  const content = payload.choices?.[0]?.message?.content;
  if (typeof content !== "string" || content.trim() === "") {
    return { error: "模型没有返回内容" };
  }
  return {
    text: content,
    usage: {
      inputTokens: payload.usage?.prompt_tokens ?? 0,
      outputTokens: payload.usage?.completion_tokens ?? 0,
    },
    finishReason: payload.choices?.[0]?.finish_reason ?? "",
  };
}

/**
 * 拼 `/chat/completions` 的完整地址。
 *
 * 用户填的 Base URL 三种写法都见过：带 `/v1`、不带 `/v1`、直接抄了整条接口地址。
 * 这里统一收敛，避免"设置里少写一个斜杠就报 404"。
 */
export function chatCompletionsUrl(baseUrl: string): string {
  const trimmed = baseUrl.trim().replace(/\/+$/, "");
  if (trimmed === "") throw new Error("接口地址为空");
  if (/\/chat\/completions$/.test(trimmed)) return trimmed;
  return trimmed + "/chat/completions";
}

function toWireMessage(message: ChatMessage): Record<string, unknown> {
  const images = message.images ?? [];
  if (images.length === 0) {
    return { role: message.role, content: message.content };
  }
  // 多模态：文本块 + 图片块。图片用 data URL，避免依赖外部可访问地址。
  const parts: Record<string, unknown>[] = [{ type: "text", text: message.content }];
  for (const url of images) {
    parts.push({ type: "image_url", image_url: { url } });
  }
  return { role: message.role, content: parts };
}

interface OpenAiDeltaPayload {
  choices?: Array<{
    delta?: { content?: string | null; reasoning_content?: string | null };
    finish_reason?: string | null;
  }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number } | null;
  error?: { message?: string; type?: string; code?: string | number } | null;
}

/**
 * 把一帧 SSE 变成领域层的 chunk。
 *
 * 三种返回：
 * - null：这帧没有可用信息（心跳、[DONE]、空 delta）；
 * - delta / reasoning：正文或思考链；
 * - finished：流结束（finish_reason 或 usage 出现时给一次，由编排层去重）。
 */
export function toChunk(event: SseEvent): LlmChunk | null {
  if (event.data === DONE_MARKER) return null;

  let payload: OpenAiDeltaPayload;
  try {
    payload = JSON.parse(event.data) as OpenAiDeltaPayload;
  } catch {
    // 厂商偶尔在流里插非 JSON 的心跳，丢掉即可；真正的错误会另走 error 分支
    return null;
  }

  if (payload.error) {
    return {
      kind: "failed",
      failure: {
        kind: "server",
        message: payload.error.message ?? "模型返回了一个错误",
      },
    };
  }

  const choice = payload.choices?.[0];
  const delta = choice?.delta;
  const reasoning = delta?.reasoning_content;
  if (typeof reasoning === "string" && reasoning.length > 0) {
    return { kind: "reasoning", text: reasoning };
  }
  if (typeof delta?.content === "string" && delta.content.length > 0) {
    return { kind: "delta", text: delta.content };
  }

  const finishReason = choice?.finish_reason ?? "";
  if (payload.usage || finishReason) {
    return {
      kind: "finished",
      usage: readUsage(payload.usage),
      finishReason,
    };
  }
  return null;
}

function readUsage(usage: OpenAiDeltaPayload["usage"]): TokenUsage {
  return {
    inputTokens: usage?.prompt_tokens ?? 0,
    outputTokens: usage?.completion_tokens ?? 0,
  };
}
