import { createFetchTransport, type LlmEndpoint, type LlmTransport } from "@totop/core";
import { createUniBufferedTransport } from "./uni-transport";

/**
 * 传输方式的选型。
 *
 * 现状（1.0-alpha）：
 * - `stream`：fetch + ReadableStream。H5 可用，已在 Node 里用真 HTTP 服务验过；
 *   App 端能不能用取决于 webview 的跨域策略，**未在真机验证**。
 * - `buffered`：uni.request 一次性返回。双端都能用，代价是首字延迟。
 *
 * App 默认走 buffered 是刻意的保守选择：能跑通，只是慢；
 * 流式在真机上翻车的表现是"点了发送没反应"，这种问题不该在 alpha 出现。
 * T13 真机验证 App 的 fetch 可用后，把默认值改回 stream 即可，调用方不用动。
 */
export type TransportMode = "stream" | "buffered";

export const DEFAULT_TRANSPORT_MODE: TransportMode = "buffered";

export function createTransport(mode: TransportMode, endpoint: () => LlmEndpoint): LlmTransport {
  if (mode === "stream") return createFetchTransport({ endpoint });
  return createUniBufferedTransport(endpoint);
}
