import {
  abortedFailure,
  buildChatBody,
  chatCompletionsUrl,
  classifyHttpFailure,
  networkFailure,
  parseCompletion,
  type LlmChunk,
  type LlmEndpoint,
  type LlmRequest,
  type LlmTransport,
} from "@totop/core";

/**
 * App 端的非流式传输（uni.request）。
 *
 * 为什么 App 端不用流式：`uni.request` 的 `enableChunked` 是微信小程序的能力，
 * Android / iOS 拿不到 chunk（见改造指南 3.4.7）。用 fetch 赌 App 的 webview
 * 不被跨域拦住，风险比收益大，所以这里老实关掉 stream，一次性拿整段。
 *
 * 代价是首字延迟等于整段生成时间（通常 10 到 30 秒），
 * 所以界面必须有分阶段的进度提示，不能只转圈。
 *
 * 与流式驱动共用同一套契约（text + usage + finishReason），
 * 编排层不知道自己拿到的是流还是一整段。
 */
export function createUniBufferedTransport(endpoint: () => LlmEndpoint): LlmTransport {
  return {
    async *stream(request: LlmRequest, signal: AbortSignal): AsyncIterable<LlmChunk> {
      const current = endpoint();
      let url: string;
      try {
        url = chatCompletionsUrl(current.baseUrl);
      } catch (error) {
        yield { kind: "failed", failure: { kind: "bad_base_url", message: describe(error) } };
        return;
      }

      const result = await postOnce(url, current.apiKey, request, signal);

      if (result.kind === "aborted") {
        yield { kind: "failed", failure: abortedFailure() };
        return;
      }
      if (result.kind === "failed") {
        yield { kind: "failed", failure: result.failure };
        return;
      }

      const parsed = parseCompletion(result.body);
      if ("error" in parsed) {
        yield { kind: "failed", failure: { kind: "parse_failed", message: parsed.error } };
        return;
      }

      if (parsed.text !== "") yield { kind: "delta", text: parsed.text };
      yield { kind: "finished", usage: parsed.usage, finishReason: parsed.finishReason };
    },
  };
}

type PostResult =
  | { kind: "ok"; body: string }
  | { kind: "aborted" }
  | { kind: "failed"; failure: ReturnType<typeof networkFailure> };

function postOnce(
  url: string,
  apiKey: string,
  request: LlmRequest,
  signal: AbortSignal,
): Promise<PostResult> {
  return new Promise<PostResult>((resolve) => {
    if (signal.aborted) {
      resolve({ kind: "aborted" });
      return;
    }

    const task = uni.request({
      url,
      method: "POST",
      timeout: 120_000,
      // 自己序列化：uni 只在 content-type 为 json 时帮我们序列化，行为在不同端不一致。
      data: JSON.stringify(buildChatBody(request, { stream: false })),
      header: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      dataType: "json",
      success: (response) => {
        const status = response.statusCode ?? 0;
        const body = typeof response.data === "string" ? response.data : JSON.stringify(response.data);
        if (status >= 400 || status === 0) {
          resolve({ kind: "failed", failure: classifyHttpFailure(status, body) });
          return;
        }
        resolve({ kind: "ok", body });
      },
      fail: (error) => {
        if (signal.aborted) {
          resolve({ kind: "aborted" });
          return;
        }
        resolve({ kind: "failed", failure: networkFailure(error.errMsg ?? "请求失败") });
      },
    });

    // 页面销毁或用户点停止时，必须真的把连接掐掉，否则请求还在后台跑、还在计费。
    const onAbort = (): void => {
      task.abort();
      resolve({ kind: "aborted" });
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function describe(error: unknown): string {
  if (error instanceof Error) return error.message;
  return "接口地址不可用";
}
