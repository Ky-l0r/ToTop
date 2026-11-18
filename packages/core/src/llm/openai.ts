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

/** 拼请求体。stream 与 include_usage 必须同时开，否则拿不到 token 用量。 */
export function buildChatBody(request: LlmRequest): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model: request.model,
    stream: true,
    stream_options: { include_usage: true },
    messages: request.messages.map(toWireMessage),
  };
  if (request.temperature !== undefined) body.temperature = request.temperature;
  if (request.maxTokens !== undefined) body.max_tokens = request.maxTokens;
  if (request.jsonMode) body.response_format = { type: "json_object" };
  return body;
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
