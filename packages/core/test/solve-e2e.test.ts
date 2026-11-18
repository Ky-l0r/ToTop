import { createServer, type Server } from "node:http";
import { afterEach, describe, expect, it } from "vitest";
import { createFetchTransport } from "../src/llm/fetch-transport";
import type { LlmEndpoint } from "../src/llm/types";
import { runSolve } from "../src/solve/orchestrator";
import type { PromptSet } from "../src/solve/prompts";

/**
 * 端到端：真 HTTP + 真四阶段编排。
 *
 * 单元测试各自都过了，但它们之间那条缝——传输层交出来的 chunk 能不能被编排层拼成一份解答——
 * 只有连起来跑一次才知道。这里用一个按提示词分流的本地服务模拟厂商。
 */
const servers: Server[] = [];

afterEach(async () => {
  await Promise.all(
    servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))),
  );
});

const PROMPTS: PromptSet = {
  version: "test",
  a: "PROMPT_A",
  b: "PROMPT_B",
  c: "PROMPT_C",
  d: "PROMPT_D",
};

const REPLIES: Record<string, string> = {
  PROMPT_A: JSON.stringify({
    subject: "math",
    grade: "高一",
    title: "二次函数闭区间最值",
    stem: "已知 f(x)=x^2-2x+1，求 [0,3] 上最小值。",
    known: ["f(x)=x^2-2x+1"],
    ask: "求最小值",
    options: [],
    choiceType: "none",
    hasFigure: false,
    stemLatex: "",
    handwriting: "partial",
    handwritingText: "学生写：配方得 (x-1)^2+1",
    progress: [],
    warnings: [],
  }),
  PROMPT_B: JSON.stringify({
    steps: [
      { idx: 1, content: "配方", why: "看顶点" },
      { idx: 2, content: "比较端点", why: "闭区间" },
    ],
    finalAnswer: "0",
    method: "配方法",
    check: "",
    confidence: 0.9,
  }),
  PROMPT_C: JSON.stringify({
    studentSteps: [],
    firstError: {
      stepIdx: 1,
      subQuestion: null,
      type: "计算失误",
      explain: "常数项算错",
      correctThinking: "先定常数项",
    },
    summaryCard: { title: "配方别丢常数", bullets: ["先配方"] },
  }),
  PROMPT_D: JSON.stringify({
    kpoints: [{ id: "KP_MATH_0002", name: "二次函数", confidence: 0.9, usage: "配方" }],
    primaryId: "KP_MATH_0002",
    unmatched: false,
  }),
};

/** 把一段 JSON 切成多帧 SSE，模拟真实分片。 */
function toSse(text: string): string[] {
  const half = Math.floor(text.length / 2);
  return [
    `data: ${JSON.stringify({ choices: [{ delta: { content: text.slice(0, half) } }] })}\n\n`,
    `data: ${JSON.stringify({ choices: [{ delta: { content: text.slice(half) } }] })}\n\n`,
    `data: ${JSON.stringify({
      choices: [{ delta: {}, finish_reason: "stop" }],
      usage: { prompt_tokens: 100, completion_tokens: 20 },
    })}\n\n`,
    "data: [DONE]\n\n",
  ];
}

async function startMockProvider(): Promise<string> {
  const server = createServer((req, res) => {
    const parts: Buffer[] = [];
    req.on("data", (part: Buffer) => parts.push(part));
    req.on("end", () => {
      const body = JSON.parse(Buffer.concat(parts).toString("utf8")) as {
        messages: Array<{ content: string }>;
      };
      const system = body.messages[0]?.content ?? "PROMPT_A";
      const reply = REPLIES[system] ?? "";

      res.writeHead(200, { "Content-Type": "text/event-stream" });
      for (const frame of toSse(reply)) res.write(frame);
      res.end();
    });
  });
  servers.push(server);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  if (typeof address === "string" || address === null) throw new Error("拿不到端口");
  return `http://127.0.0.1:${address.port}`;
}

describe("传输层与编排层连起来", () => {
  it("四个阶段都走真 HTTP，最后拼出一份完整解答", async () => {
    const baseUrl = await startMockProvider();
    const endpoint: LlmEndpoint = { baseUrl: baseUrl + "/v1", apiKey: "sk-test" };
    const events: string[] = [];

    const outcome = await runSolve(
      {
        images: ["data:image/jpeg;base64,AAA"],
        studentText: "卡在配方",
        mode: "GUIDED",
        candidateKpoints: [{ id: "KP_MATH_0002", name: "二次函数" }],
        signal: new AbortController().signal,
      },
      {
        transport: createFetchTransport({ endpoint: () => endpoint }),
        prompts: PROMPTS,
        model: "test-model",
        sleep: async () => undefined,
        onEvent: (event) => events.push(event.kind === "stage" ? `${event.stage}:${event.state}` : event.kind),
      },
    );

    expect(outcome.spec.title).toBe("二次函数闭区间最值");
    expect(outcome.solution.finalAnswer).toBe("0");
    expect(outcome.report?.firstError?.type).toBe("计算失误");
    expect(outcome.kpoints?.primaryId).toBe("KP_MATH_0002");
    // 每阶段两次 delta，加上 finished 的用量
    expect(outcome.usage).toEqual({ inputTokens: 400, outputTokens: 80 });
    expect(events).toContain("A:done");
    expect(events).toContain("D:done");
  });
});
