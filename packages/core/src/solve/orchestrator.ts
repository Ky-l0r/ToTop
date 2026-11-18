import { classifyHttpFailure } from "../llm/errors";
import { shouldRetry, backoffMs } from "../llm/retry";
import type { LlmFailure, LlmTransport, TokenUsage } from "../llm/types";
import { addUsage, EMPTY_USAGE } from "../llm/types";
import {
  parseErrorReport,
  parseKpointMatches,
  parseQuestionSpec,
  parseStandardSolution,
  type ErrorReportSpec,
  type KpointMatchSpec,
  type QuestionSpec,
  type StandardSolutionSpec,
} from "./contracts";
import type { PromptSet } from "./prompts";
import { skeletonInstruction } from "./question-type";

export type Stage = "A" | "B" | "C" | "D";

export type SolveEvent =
  | { kind: "stage"; stage: Stage; state: "start" | "done" | "skipped" }
  | { kind: "delta"; text: string }
  | { kind: "reasoning"; text: string }
  | { kind: "usage"; usage: TokenUsage };

export interface SolveInput {
  /** 题目图片（data URL 或本地路径）。阶段 A 直接看图，不做本地 OCR。 */
  images: string[];
  /** 学生写的一句补充说明；可以为空。 */
  studentText: string;
  mode: "DIRECT" | "GUIDED";
  /** 候选知识点（已按科目收敛）。为空时跳过阶段 D，这是正确行为。 */
  candidateKpoints: ReadonlyArray<{ id: string; name: string }>;
  signal: AbortSignal;
}

export interface SolveDeps {
  transport: LlmTransport;
  prompts: PromptSet;
  model: string;
  onEvent?: (event: SolveEvent) => void;
  /** 重试等待，测试里换成不睡的实现。 */
  sleep?: (ms: number) => Promise<void>;
}

export interface SolveOutcome {
  spec: QuestionSpec;
  solution: StandardSolutionSpec;
  report: ErrorReportSpec | null;
  kpoints: KpointMatchSpec | null;
  usage: TokenUsage;
  /** 各阶段降级处理过的内容，要写进留痕。 */
  notes: string[];
}

/** 任何一步失败都抛这个，带上是哪个阶段。 */
export class SolveError extends Error {
  constructor(
    readonly stage: Stage,
    readonly failure: LlmFailure,
    readonly parseReason = "",
  ) {
    super(failure.message);
    this.name = "SolveError";
  }
}

/**
 * 四阶段编排。
 *
 * 核心约束：**阶段 B 的输入里根本没有学生作答**。
 * 这不是靠提示词嘱咐，而是在 [buildSolvePayload] 里物理隔离——
 * 字段压根不往里放，将来有人想"顺手带上"必须先改这个函数，会被测试拦住。
 */
export async function runSolve(input: SolveInput, deps: SolveDeps): Promise<SolveOutcome> {
  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const notes: string[] = [];
  let usage: TokenUsage = EMPTY_USAGE;
  const emit = deps.onEvent ?? (() => undefined);

  const callStage = async (
    stage: Stage,
    prompt: string,
    userText: string,
    images: string[],
    streamDeltas: boolean,
  ): Promise<string> => {
    emit({ kind: "stage", stage, state: "start" });
    let attempt = 0;
    for (;;) {
      if (input.signal.aborted) {
        throw new SolveError(stage, { kind: "aborted", message: "已取消" });
      }
      attempt += 1;
      try {
        const text = await runSingleCall({
          prompt,
          userText,
          images,
          streamDeltas,
          transport: deps.transport,
          model: deps.model,
          signal: input.signal,
          emit,
          onUsage: (delta) => {
            usage = addUsage(usage, delta);
          },
        });
        emit({ kind: "stage", stage, state: "done" });
        return text;
      } catch (error) {
        const failure = toFailure(error);
        if (failure.kind === "aborted" || !shouldRetry(failure.kind, attempt)) {
          throw new SolveError(stage, failure);
        }
        await sleep(backoffMs(attempt));
      }
    }
  };

  // 阶段 A：识题。看图，产出题干、选项、题型、手写转写与标题。
  const aRaw = await callStage(
    "A",
    deps.prompts.a,
    input.studentText.trim() === "" ? "（学生没有写补充说明）" : input.studentText,
    input.images,
    false,
  );
  const a = parseQuestionSpec(aRaw);
  if (!a.ok) {
    throw new SolveError("A", { kind: "parse_failed", message: a.reason }, a.reason);
  }
  notes.push(...a.notes);
  const spec = a.value;

  // 阶段 B：独立求解。只给题目，另按"学科 × 题型"追加骨架要求（写进系统提示词，不进用户数据）。
  const bPayload = buildSolvePayload(spec);
  const bPrompt = deps.prompts.b + "\n\n" + skeletonInstruction(spec.subject, spec.questionType);
  const bRaw = await callStage("B", bPrompt, JSON.stringify(bPayload), [], true);
  const b = parseStandardSolution(bRaw);
  if (!b.ok) {
    throw new SolveError("B", { kind: "parse_failed", message: b.reason }, b.reason);
  }
  notes.push(...b.notes);

  // 阶段 C：错因定位。这里才允许看到学生写了什么。
  const cRaw = await callStage(
    "C",
    deps.prompts.c,
    JSON.stringify({
      question: bPayload,
      standardSolution: b.value,
      studentWork: spec.handwritingText,
      studentNote: input.studentText,
    }),
    [],
    false,
  );
  const c = parseErrorReport(cRaw);
  if (!c.ok) {
    throw new SolveError("C", { kind: "parse_failed", message: c.reason }, c.reason);
  }
  notes.push(...c.notes);

  // 阶段 D：知识点映射。没有候选就别发请求，这是省钱也是正确行为。
  let kpoints: KpointMatchSpec | null = null;
  if (input.candidateKpoints.length === 0) {
    emit({ kind: "stage", stage: "D", state: "skipped" });
  } else {
    const dRaw = await callStage(
      "D",
      deps.prompts.d,
      JSON.stringify({
        question: bPayload,
        standardSolution: {
          method: b.value.method,
          steps: b.value.steps.map((step) => step.content),
        },
        candidateKpoints: input.candidateKpoints,
      }),
      [],
      false,
    );
    const d = parseKpointMatches(dRaw);
    if (!d.ok) {
      // 知识点映射失败不该让整道题白算：解答与错因都在，只是没挂知识点。
      notes.push(`知识点映射失败：${d.reason}`);
      emit({ kind: "stage", stage: "D", state: "skipped" });
    } else {
      notes.push(...d.notes);
      kpoints = d.value;
    }
  }

  emit({ kind: "usage", usage });
  return { spec, solution: b.value, report: c.value, kpoints, usage, notes };
}

/**
 * 阶段 B 的输入。
 *
 * 只放题目本身。**故意不包含 handwriting / handwritingText / progress**：
 * 模型一旦看到学生的写法，会顺着错误思路"解出一个相符的答案"，
 * 错因定位就失去意义。这是本项目最不能被"优化"掉的一条。
 */
export function buildSolvePayload(spec: QuestionSpec): Record<string, unknown> {
  return {
    subject: spec.subject,
    grade: spec.grade,
    stem: spec.stem,
    known: spec.known,
    ask: spec.ask,
    options: spec.options,
    choiceType: spec.choiceType,
    hasFigure: spec.hasFigure,
  };
}

export interface SingleCallArgs {
  /** 系统提示词。 */
  prompt: string;
  userText: string;
  images?: string[];
  transport: LlmTransport;
  model: string;
  signal: AbortSignal;
  /** 之前的对话（多轮场景）。 */
  history?: Array<{ role: "user" | "assistant"; content: string }>;
  /** 边跑边把正文推给界面（拍题的阶段 B 用）。 */
  streamDeltas?: boolean;
  emit?: (event: SolveEvent) => void;
  onUsage?: (usage: TokenUsage) => void;
  temperature?: number;
  /** 要求厂商返回严格 JSON；材料解析、复习资料都走这条。 */
  jsonMode?: boolean;
}

/**
 * 跑一次模型调用，把流拼成完整正文。
 *
 * 抽出来是因为除四阶段之外还有几处"一次调用、要一份 JSON"的场景
 * （标准答案解析、复习资料生成）。它们必须和四阶段共享同一套
 * 空输出 / 截断判定，否则换个入口就换了一种失败表现。
 */
export async function runSingleCall(args: SingleCallArgs): Promise<string> {
  const emit = args.emit ?? (() => undefined);
  const onUsage = args.onUsage ?? (() => undefined);
  const streamDeltas = args.streamDeltas ?? false;
  let text = "";
  let lastFinish = "";

  for await (const chunk of args.transport.stream(
    {
      model: args.model,
      temperature: args.temperature ?? 0.2,
      jsonMode: args.jsonMode ?? true,
      messages: [
        { role: "system", content: args.prompt },
        ...(args.history ?? []),
        { role: "user", content: args.userText, images: args.images ?? [] },
      ],
    },
    args.signal,
  )) {
    switch (chunk.kind) {
      case "delta":
        text += chunk.text;
        // 只有阶段 B 的正文值得边跑边显示；A/C/D 是 JSON，边流边显示只是噪音。
        if (streamDeltas) emit({ kind: "delta", text: chunk.text });
        break;
      case "reasoning":
        if (streamDeltas) emit({ kind: "reasoning", text: chunk.text });
        break;
      case "finished":
        lastFinish = chunk.finishReason;
        onUsage(chunk.usage);
        break;
      case "failed":
        throw new SingleCallError(chunk.failure);
    }
  }

  if (text.trim() === "") {
    throw new SingleCallError({ kind: "output_empty", message: "模型没有输出内容，重试一次" });
  }
  if (lastFinish === "length") {
    throw new SingleCallError({
      kind: "output_truncated",
      message: "输出被长度上限截断了，把题拆小一点再问",
    });
  }
  return text;
}

/** 单次调用失败。`failure` 已经分好档，调用方直接拿去显示。 */
export class SingleCallError extends Error {
  constructor(readonly failure: LlmFailure) {
    super(failure.message);
    this.name = "SingleCallError";
  }
}

function toFailure(error: unknown): LlmFailure {
  if (error instanceof SingleCallError) return error.failure;
  if (error instanceof Error) {
    if (error.name === "AbortError") return { kind: "aborted", message: "已取消" };
    return { kind: "network", message: error.message || "请求失败" };
  }
  return classifyHttpFailure(0, "");
}
