import { abortedFailure, classifyHttpFailure, networkFailure } from "./errors";
import { buildChatBody, chatCompletionsUrl, toChunk } from "./openai";
import { SseParser } from "./sse";
import type { LlmChunk, LlmEndpoint, LlmFailure, LlmRequest, LlmTransport } from "./types";

/**
 * 基于 fetch 的流式传输。
 *
 * 用的是 Web 标准（fetch + ReadableStream + TextDecoder），不是 uni 的通道：
 * H5 与 Node 都原生支持，App 的 webview 里通常也有——但 App 端会不会被跨域拦住，
 * 只能在真机上验，所以这一层被抽成可替换的驱动，接不上就换非流式。
 *
 * 这里只做三件事：发请求、把字节切回文本、把 SSE 帧交给协议层。
 * 重试、增量渲染、上下文管理都不在这里。
 */
export interface FetchTransportOptions {
  /** 每次调用现取端点：用户中途改了 Base URL 或 Key，下一次请求就生效。 */
  endpoint: () => LlmEndpoint;
  /** 测试注入用；生产不传，走全局 fetch。 */
  fetchImpl?: typeof fetch;
  /** 额外请求头，例如自建网关需要的组织标识。 */
  headers?: () => Record<string, string>;
  /**
   * 整条请求的超时（毫秒），默认 120 秒。
   *
   * 没有超时的 fetch 会一直挂着：学生看到的是"点了发送没反应"，
   * 而进程还在后台等一个永远不来的响应。非流式的 uni 通道已经设了同样的 120 秒，
   * 两条路必须一致，否则换驱动就换了一种故障表现。
   */
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 120_000;

export function createFetchTransport(options: FetchTransportOptions): LlmTransport {
  const doFetch: typeof fetch =
    options.fetchImpl ?? ((input, init) => globalThis.fetch(input, init));
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  return {
    async *stream(request: LlmRequest, signal: AbortSignal): AsyncIterable<LlmChunk> {
      const endpoint = options.endpoint();

      // 超时与"用户取消"共用一个 AbortController，靠 timedOut 区分是谁掐的：
      // 两者对学生意味着完全不同的下一步（等一等再试 / 自己按的，不用管）。
      const controller = new AbortController();
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, timeoutMs);
      const onAbort = (): void => controller.abort();
      signal.addEventListener("abort", onAbort, { once: true });

      let response: Response;
      try {
        response = await doFetch(chatCompletionsUrl(endpoint.baseUrl), {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            Accept: "text/event-stream",
            Authorization: `Bearer ${endpoint.apiKey}`,
            ...(options.headers?.() ?? {}),
          },
          body: JSON.stringify(buildChatBody(request, { stream: true })),
          signal: controller.signal,
        });
      } catch (error) {
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        yield { kind: "failed", failure: failureOf(error, timedOut, signal, timeoutMs) };
        return;
      }

      if (!response.ok) {
        const body = await readTextSafely(response);
        yield { kind: "failed", failure: classifyHttpFailure(response.status, body) };
        return;
      }

      const body = response.body;
      if (!body) {
        // 网关偶尔忽略 stream 参数、一次性返回整段 SSE。按整段解析，行为一致。
        const whole = await readTextSafely(response);
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        for (const chunk of parseAll(whole)) yield chunk;
        return;
      }

      const reader = body.getReader();
      const decoder = new TextDecoder();
      const parser = new SseParser();

      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!value) continue;
          const text = decoder.decode(value, { stream: true });
          for (const chunk of feed(parser, text)) yield chunk;
        }
        const tail = decoder.decode();
        for (const chunk of feed(parser, tail)) yield chunk;
        for (const event of parser.flush()) {
          const chunk = toChunk(event);
          if (chunk) yield chunk;
        }
      } catch (error) {
        yield { kind: "failed", failure: failureOf(error, timedOut, signal, timeoutMs) };
      } finally {
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        // 不 release 也能被 GC，但显式释放能让 abort 立刻断开底层连接。
        try {
          reader.releaseLock();
        } catch {
          // 已经释放过，忽略。
        }
      }
    },
  };
}

function* feed(parser: SseParser, text: string): Generator<LlmChunk> {
  if (text === "") return;
  for (const event of parser.push(text)) {
    const chunk = toChunk(event);
    if (chunk) yield chunk;
  }
}

function* parseAll(raw: string): Generator<LlmChunk> {
  const parser = new SseParser();
  yield* feed(parser, raw);
  for (const event of parser.flush()) {
    const chunk = toChunk(event);
    if (chunk) yield chunk;
  }
}

async function readTextSafely(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

/**
 * 把异常翻译成失败档。
 *
 * 超时也走 network 档：对学生来说两者都是"没连上"，但文案要写明是超时，
 * 否则他会一直重试同一个已经挂住的地址。
 */
function failureOf(
  error: unknown,
  timedOut: boolean,
  signal: AbortSignal,
  timeoutMs: number,
): LlmFailure {
  if (timedOut) {
    return {
      kind: "network",
      message: `等了 ${Math.round(timeoutMs / 1000)} 秒还没回应，可能是网络或接口地址的问题`,
    };
  }
  if (signal.aborted) return abortedFailure();
  return networkFailure(describe(error));
}

function describe(error: unknown): string {
  if (error instanceof Error) return error.message;
  return "网络请求失败";
}
