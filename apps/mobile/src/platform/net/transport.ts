import { createFetchTransport, type LlmEndpoint, type LlmTransport } from "@totop/core";
import { createUniBufferedTransport } from "./uni-transport";

/**
 * 传输方式的选型。
 *
 * - `stream`：fetch + ReadableStream，能边跑边渲染。H5 可用（已用真 HTTP 在 Node 里验过）。
 * - `buffered`：uni.request 一次性返回，双端都能用，代价是首字延迟。
 *
 * 为什么按平台自动选：H5 下 fetch 一定可用，没理由让学生对着白屏等 20 秒；
 * 而 App 端 `uni.request` 拿不到 chunked（`enableChunked` 是微信小程序的能力），
 * fetch 会不会被 webview 的跨域策略拦住，只能在真机上验。所以 App 上保守走非流式。
 *
 * 真机验证 App 的 fetch 可用后，把这里的 app 分支改成 `stream` 即可，调用方不用动。
 */
export type TransportMode = "stream" | "buffered";

/** H5 走流式，App 暂时走非流式。取不到平台信息时按保守的一档。 */
export function defaultTransportMode(): TransportMode {
  try {
    const info = uni.getSystemInfoSync();
    return info.uniPlatform === "web" ? "stream" : "buffered";
  } catch {
    return "buffered";
  }
}

export function createTransport(mode: TransportMode, endpoint: () => LlmEndpoint): LlmTransport {
  if (mode === "stream") return createFetchTransport({ endpoint });
  return createUniBufferedTransport(endpoint);
}
