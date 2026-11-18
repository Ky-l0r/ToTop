import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { createFetchTransport } from "../src/llm/fetch-transport";
import { chatCompletionsUrl, parseCompletion } from "../src/llm/openai";
import type { LlmChunk, LlmEndpoint, LlmRequest } from "../src/llm/types";

/**
 * 起一个真的 HTTP 服务来测传输层。
 *
 * 为什么不 mock fetch：这一层最容易错的地方是"字节怎么变成帧"——
 * chunk 边界、连接被掐、401 的响应体、abort 之后连接有没有真的断。
 * mock 掉的正是要验的东西。
 */
const servers: Server[] = [];

afterEach(async () => {
  await Promise.all(
    servers.splice(0).map(
      (server) =>
        new Promise<void>((resolve) => {
          // 取消类用例会留下半开的连接，不主动断掉会让 close 一直等
          server.closeAllConnections();
          server.close(() => resolve());
        }),
    ),
  );
});

async function startServer(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<string> {
  const server = createServer((req, res) => {
    const parts: Buffer[] = [];
    req.on("data", (part: Buffer) => parts.push(part));
    req.on("end", () => handler(req, res, Buffer.concat(parts).toString("utf8")));
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (typeof address === "string" || address === null) throw new Error("拿不到端口");
  return `http://127.0.0.1:${address.port}`;
}

const request: LlmRequest = {
  model: "test-model",
  messages: [
    { role: "system", content: "s" },
    { role: "user", content: "u" },
  ],
};

function endpoint(baseUrl: string): LlmEndpoint {
  return { baseUrl, apiKey: "sk-test" };
}

async function collect(iterable: AsyncIterable<LlmChunk>): Promise<LlmChunk[]> {
  const chunks: LlmChunk[] = [];
  for await (const chunk of iterable) chunks.push(chunk);
  return chunks;
}

describe("fetch 流式传输", () => {
  it("把分片到达的 SSE 还原成有序 chunk，并在最后给出用量", async () => {
    const baseUrl = await startServer((req, res) => {
      expect(req.headers.authorization).toBe("Bearer sk-test");
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write('data: {"choices":[{"delta":{"content":"答"}}]}\n\n');
      res.write('data: {"choices":[{"delta":{"content":"案"}}]}\n\n');
      res.write('data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2}}\n\n');
      res.write("data: [DONE]\n\n");
      res.end();
    });

    const transport = createFetchTransport({ endpoint: () => endpoint(baseUrl) });
    const chunks = await collect(transport.stream(request, new AbortController().signal));

    expect(chunks).toEqual([
      { kind: "delta", text: "答" },
      { kind: "delta", text: "案" },
      {
        kind: "finished",
        usage: { inputTokens: 7, outputTokens: 2 },
        finishReason: "stop",
      },
    ]);
  });

  it("请求体开着流，并且要了 usage", async () => {
    let received = "";
    const baseUrl = await startServer((_req, res, body) => {
      received = body;
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write("data: [DONE]\n\n");
      res.end();
    });

    const transport = createFetchTransport({ endpoint: () => endpoint(baseUrl) });
    await collect(transport.stream(request, new AbortController().signal));

    const payload = JSON.parse(received) as Record<string, unknown>;
    expect(payload.stream).toBe(true);
    expect(payload.stream_options).toEqual({ include_usage: true });
    expect(payload.model).toBe("test-model");
  });

  it("401 归到 Key 无效，不重试也不吞掉", async () => {
    const baseUrl = await startServer((_req, res) => {
      res.writeHead(401, { "Content-Type": "application/json" });
      res.end('{"error":{"message":"invalid api key"}}');
    });

    const transport = createFetchTransport({ endpoint: () => endpoint(baseUrl) });
    const chunks = await collect(transport.stream(request, new AbortController().signal));

    expect(chunks).toHaveLength(1);
    expect(chunks[0]).toMatchObject({ kind: "failed", failure: { kind: "unauthorized", status: 401 } });
  });

  it("404 且响应里提到 model，判成模型名错而不是地址错", async () => {
    const baseUrl = await startServer((_req, res) => {
      res.writeHead(404, { "Content-Type": "application/json" });
      res.end('{"error":{"message":"model not found"}}');
    });

    const transport = createFetchTransport({ endpoint: () => endpoint(baseUrl) });
    const chunks = await collect(transport.stream(request, new AbortController().signal));
    expect(chunks[0]).toMatchObject({ kind: "failed", failure: { kind: "model_not_found" } });
  });

  it("中途取消：给出已取消，而不是网络错误", async () => {
    const baseUrl = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write('data: {"choices":[{"delta":{"content":"一"}}]}\n\n');
      // 故意不结束：让"取消"成为唯一的收尾方式。
      // 之前这里用 200ms 后 end()，机器忙的时候 end 会先于 abort 到达，
      // 用例偶发失败——测时序的用例不能依赖"谁先到"。
    });

    const controller = new AbortController();
    const transport = createFetchTransport({ endpoint: () => endpoint(baseUrl) });
    const chunks: LlmChunk[] = [];
    for await (const chunk of transport.stream(request, controller.signal)) {
      chunks.push(chunk);
      if (chunk.kind === "delta") controller.abort();
    }

    expect(chunks[0]).toEqual({ kind: "delta", text: "一" });
    expect(chunks.at(-1)).toMatchObject({ kind: "failed", failure: { kind: "aborted" } });
  });

  it("服务端不回应时按超时收尾，文案说的是超时而不是网络错误", async () => {
    const baseUrl = await startServer(() => {
      // 只握手，不回任何数据，也不结束
    });

    const transport = createFetchTransport({
      endpoint: () => endpoint(baseUrl),
      timeoutMs: 50,
    });
    const chunks = await collect(transport.stream(request, new AbortController().signal));

    expect(chunks).toHaveLength(1);
    expect(chunks[0]).toMatchObject({ kind: "failed", failure: { kind: "network" } });
    const message = chunks[0]?.kind === "failed" ? chunks[0].failure.message : "";
    expect(message).toContain("还没回应");
  });

  it("连不上时给网络失败，不抛到调用方", async () => {
    // 一个几乎肯定没有服务在听的端口
    const transport = createFetchTransport({ endpoint: () => endpoint("http://127.0.0.1:9") });
    const chunks = await collect(transport.stream(request, new AbortController().signal));
    expect(chunks[0]).toMatchObject({ kind: "failed", failure: { kind: "network" } });
  });
});

describe("地址收敛", () => {
  it("三种常见的 Base URL 写法都拼得对", () => {
    expect(chatCompletionsUrl("https://api.example.com/v1")).toBe(
      "https://api.example.com/v1/chat/completions",
    );
    expect(chatCompletionsUrl("https://api.example.com/v1/")).toBe(
      "https://api.example.com/v1/chat/completions",
    );
    expect(chatCompletionsUrl("https://api.example.com/v1/chat/completions")).toBe(
      "https://api.example.com/v1/chat/completions",
    );
  });

  it("空地址直接报错，不拼出个 /chat/completions 去请求", () => {
    expect(() => chatCompletionsUrl("  ")).toThrowError(/接口地址为空/);
  });
});

describe("非流式响应解析", () => {
  it("正文与用量都读出来", () => {
    const raw = JSON.stringify({
      choices: [{ message: { content: "答案为 0" }, finish_reason: "stop" }],
      usage: { prompt_tokens: 11, completion_tokens: 5 },
    });
    expect(parseCompletion(raw)).toEqual({
      text: "答案为 0",
      usage: { inputTokens: 11, outputTokens: 5 },
      finishReason: "stop",
    });
  });

  it("正文为空时报错，不返回空字符串", () => {
    const raw = JSON.stringify({ choices: [{ message: { content: "   " } }] });
    expect(parseCompletion(raw)).toHaveProperty("error");
  });
});
