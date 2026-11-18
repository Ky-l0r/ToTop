import { describe, expect, it } from "vitest";
import type { LlmChunk, LlmRequest, LlmTransport } from "../src/llm/types";
import type { PromptSet } from "../src/solve/prompts";
import { runSolve, SolveError, type SolveEvent } from "../src/solve/orchestrator";

const PROMPTS: PromptSet = { version: "test", a: "PROMPT_A", b: "PROMPT_B", c: "PROMPT_C", d: "PROMPT_D" };

const STAGE_A = JSON.stringify({
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
  progress: [{ no: 1, state: "done" }],
  warnings: [],
});

const STAGE_B = JSON.stringify({
  steps: [
    { idx: 1, content: "配方", why: "顶点式便于看最值" },
    { idx: 2, content: "比较端点", why: "闭区间要比较" },
  ],
  finalAnswer: "0",
  method: "配方法",
  check: "代入验证",
  confidence: 0.9,
});

const STAGE_C = JSON.stringify({
  studentSteps: [{ idx: 1, content: "配方", status: "wrong", alignTo: 1 }],
  firstError: {
    stepIdx: 1,
    subQuestion: null,
    type: "计算失误",
    explain: "常数项算错",
    correctThinking: "先算 −2x 的配方项",
  },
  summaryCard: { title: "配方别丢常数", bullets: ["先配方"] },
});

const STAGE_D = JSON.stringify({
  kpoints: [{ id: "KP_MATH_0002", name: "二次函数", confidence: 0.9, usage: "配方求最值" }],
  primaryId: "KP_MATH_0002",
  unmatched: false,
});

class ScriptedTransport implements LlmTransport {
  requests: LlmRequest[] = [];
  private failures: Record<string, number>;

  constructor(
    private readonly replies: Record<string, string>,
    failures: Record<string, number> = {},
  ) {
    this.failures = { ...failures };
  }

  async *stream(request: LlmRequest): AsyncIterable<LlmChunk> {
    this.requests.push(request);
    const marker = request.messages[0]?.content ?? "";
    const key = marker.replace("PROMPT_", "");

    const remaining = this.failures[key] ?? 0;
    if (remaining > 0) {
      this.failures[key] = remaining - 1;
      yield { kind: "failed", failure: { kind: "server", message: "服务端 500" } };
      return;
    }

    const text = this.replies[key] ?? "";
    // 故意切碎：顺带验证编排层拼接增量
    yield { kind: "delta", text: text.slice(0, 10) };
    yield { kind: "delta", text: text.slice(10) };
    yield { kind: "finished", usage: { inputTokens: 50, outputTokens: 10 }, finishReason: "stop" };
  }
}

function baseInput(overrides: Partial<Parameters<typeof runSolve>[0]> = {}) {
  return {
    images: ["data:image/jpeg;base64,AAA"],
    studentText: "我这题卡在配方",
    mode: "GUIDED" as const,
    candidateKpoints: [{ id: "KP_MATH_0002", name: "二次函数" }],
    signal: new AbortController().signal,
    ...overrides,
  };
}

async function run(transport: LlmTransport, input = baseInput(), events: SolveEvent[] = []) {
  return await runSolve(input, {
    transport,
    prompts: PROMPTS,
    model: "test-model",
    sleep: async () => undefined,
    onEvent: (event) => events.push(event),
  });
}

describe("四阶段编排", () => {
  it("按 A→B→C→D 顺序跑完，产出四段结果", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    const events: SolveEvent[] = [];
    const outcome = await run(transport, baseInput(), events);

    expect(transport.requests.map((r) => r.messages[0]?.content)).toEqual([
      "PROMPT_A",
      "PROMPT_B",
      "PROMPT_C",
      "PROMPT_D",
    ]);
    expect(outcome.spec.title).toBe("二次函数闭区间最值");
    expect(outcome.solution.finalAnswer).toBe("0");
    expect(outcome.report?.firstError?.type).toBe("计算失误");
    expect(outcome.kpoints?.primaryId).toBe("KP_MATH_0002");

    const started = events.filter((e) => e.kind === "stage" && e.state === "start");
    expect(started).toHaveLength(4);
  });

  it("阶段 B 的输入里没有学生作答——这是物理隔离，不是提示词约束", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    await run(transport);

    const bRequest = transport.requests[1];
    const payload = JSON.parse(bRequest?.messages[1]?.content ?? "{}") as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual([
      "ask",
      "choiceType",
      "grade",
      "hasFigure",
      "known",
      "options",
      "stem",
      "subject",
    ]);
    const serialized = JSON.stringify(payload);
    expect(serialized).not.toContain("学生写");
    expect(serialized).not.toContain("我这题卡在配方");
    expect(serialized).not.toContain("progress");
  });

  it("只有阶段 B 的正文会边跑边推给界面", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    const events: SolveEvent[] = [];
    await run(transport, baseInput(), events);
    const deltas = events.filter((e) => e.kind === "delta");
    expect(deltas.length).toBeGreaterThan(0);
    // 四个阶段各切两段，但只有 B 会推 delta
    expect(deltas.map((d) => (d.kind === "delta" ? d.text : "")).join("")).toBe(STAGE_B);
  });

  it("没有候选知识点时跳过阶段 D，且不花这次请求", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    const events: SolveEvent[] = [];
    const outcome = await run(transport, baseInput({ candidateKpoints: [] }), events);

    expect(transport.requests).toHaveLength(3);
    expect(outcome.kpoints).toBeNull();
    expect(events.some((e) => e.kind === "stage" && e.stage === "D" && e.state === "skipped")).toBe(
      true,
    );
  });

  it("四阶段的 token 用量累加", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    const outcome = await run(transport);
    expect(outcome.usage).toEqual({ inputTokens: 200, outputTokens: 40 });
  });

  it("服务端 5xx 重试一次后成功，且不重复计费两次的正文", async () => {
    const transport = new ScriptedTransport(
      { A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D },
      { B: 1 },
    );
    const outcome = await run(transport);
    expect(outcome.solution.finalAnswer).toBe("0");
    // A、B（失败一次）、B、C、D
    expect(transport.requests).toHaveLength(5);
  });

  it("阶段 A 输出不是 JSON 时，直接以解析失败收场，不继续跑", async () => {
    const transport = new ScriptedTransport({ A: "我看不清这张图", B: STAGE_B });
    await expect(run(transport)).rejects.toBeInstanceOf(SolveError);
    expect(transport.requests).toHaveLength(1);
  });

  it("取消后立刻停，不再发下一阶段", async () => {
    const controller = new AbortController();
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: STAGE_D });
    controller.abort();
    await expect(run(transport, baseInput({ signal: controller.signal }))).rejects.toMatchObject({
      stage: "A",
    });
    expect(transport.requests).toHaveLength(0);
  });

  it("阶段 D 解析失败不拖垮整题，只记一笔", async () => {
    const transport = new ScriptedTransport({ A: STAGE_A, B: STAGE_B, C: STAGE_C, D: "看不懂" });
    const outcome = await run(transport);
    expect(outcome.kpoints).toBeNull();
    expect(outcome.notes.join()).toContain("知识点映射失败");
    expect(outcome.solution.finalAnswer).toBe("0");
  });
});
