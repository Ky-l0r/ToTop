import {
  DEFAULT_TRANSPORT_MODE,
  createTransport,
} from "@/platform/net/transport";
import { getDatabase } from "@/services/db";
import { PROMPTS } from "@/solve/prompts";
import {
  EMPTY_USAGE,
  runSolve,
  SETTINGS_KEYS,
  SolveError,
  type LlmFailure,
  type SolveEvent,
  type SolveOutcome,
  type Stage,
  type TokenUsage,
} from "@totop/core";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/** 讲解模式。与 0.8.0 的两档一致：直接给全解 / 先给方向。 */
export type TeachingMode = "DIRECT" | "GUIDED";

export type SolvePhase = "idle" | "running" | "done" | "failed";

const STAGE_LABEL: Record<Stage, string> = {
  A: "正在看清题目",
  B: "正在解题",
  C: "正在找第一个错在哪",
  D: "正在关联知识点",
};

/**
 * 拍题页的 store。
 *
 * 只存这一页需要的东西：一次只解一道题，没有会话列表（1.0 的多轮追问在 T7 之后再说）。
 * 状态机刻意做小：idle / running / done / failed —— 0.8.0 把"有没有图、有没有字、有没有解"
 * 全塞进一个枚举，结果"页面切回来内容没了"这类问题查了很久。
 */
export const useSolveStore = defineStore("solve", () => {
  // ---------- 配置 ----------
  const baseUrl = ref("");
  const model = ref("");
  /**
   * API Key 只在内存里。
   *
   * 不落盘是有意的：系统钥匙串要等原生插件（T12），在那之前把 Key 写进
   * settings 表或 uni storage 都是明文，等于把学生的 Key 交给任何一个能读沙箱的进程。
   * 代价是杀掉进程要重填，界面上必须说清楚。
   */
  const apiKey = ref("");
  const configError = ref("");

  const configured = computed(
    () => baseUrl.value.trim() !== "" && model.value.trim() !== "" && apiKey.value.trim() !== "",
  );

  // ---------- 输入 ----------
  const mode = ref<TeachingMode>("GUIDED");
  const draft = ref("");

  // ---------- 运行 ----------
  const phase = ref<SolvePhase>("idle");
  const stage = ref<Stage | null>(null);
  const streamText = ref("");
  const outcome = ref<SolveOutcome | null>(null);
  const failure = ref<LlmFailure | null>(null);
  const usage = ref<TokenUsage>(EMPTY_USAGE);

  let controller: AbortController | null = null;

  const running = computed(() => phase.value === "running");
  const title = computed(() => outcome.value?.spec.title ?? "拍题");
  const stageLabel = computed(() => {
    if (phase.value === "running") return stage.value ? STAGE_LABEL[stage.value] : "正在开始";
    if (phase.value === "done") return "解完了";
    return "";
  });
  const canSend = computed(
    () => !running.value && draft.value.trim() !== "" && configured.value,
  );

  /** 读取已保存的端点与模型名。Key 读不回来，只能每次手填。 */
  async function loadConfig(): Promise<void> {
    configError.value = "";
    try {
      const { settings } = await getDatabase();
      baseUrl.value = (await settings.get(SETTINGS_KEYS.llmBaseUrl)) ?? "";
      model.value = (await settings.get(SETTINGS_KEYS.llmModel)) ?? "";
    } catch (error) {
      configError.value = error instanceof Error ? error.message : "读不到本地设置";
    }
  }

  /** 保存端点与模型名（不含 Key）。 */
  async function saveEndpoint(): Promise<void> {
    configError.value = "";
    try {
      const { settings } = await getDatabase();
      await settings.put(SETTINGS_KEYS.llmBaseUrl, baseUrl.value.trim());
      await settings.put(SETTINGS_KEYS.llmModel, model.value.trim());
    } catch (error) {
      // 存不下也不能挡住使用：这次填的值仍在内存里，下次进来手填一次就好。
      const detail = error instanceof Error ? error.message : "本地库不可用";
      configError.value = `这次能直接用，但没保存下来：${detail}`;
    }
  }

  function setMode(next: TeachingMode): void {
    mode.value = next;
  }

  function stop(): void {
    controller?.abort();
  }

  async function send(): Promise<void> {
    if (!canSend.value) return;

    phase.value = "running";
    stage.value = "A";
    streamText.value = "";
    outcome.value = null;
    failure.value = null;
    usage.value = EMPTY_USAGE;

    controller = new AbortController();
    try {
      const result = await runSolve(
        {
          images: [],
          studentText: draft.value.trim(),
          mode: mode.value,
          // 知识点候选要等知识点仓储（T10）。为空时阶段 D 直接跳过，不浪费一次请求。
          candidateKpoints: [],
          signal: controller.signal,
        },
        {
          transport: createTransport(DEFAULT_TRANSPORT_MODE, () => ({
            baseUrl: baseUrl.value,
            apiKey: apiKey.value,
          })),
          prompts: PROMPTS,
          model: model.value.trim(),
          onEvent: (event: SolveEvent) => {
            if (event.kind === "stage" && event.state === "start") stage.value = event.stage;
            if (event.kind === "delta") streamText.value += event.text;
            if (event.kind === "usage") usage.value = event.usage;
          },
        },
      );
      outcome.value = result;
      usage.value = result.usage;
      phase.value = "done";
    } catch (error) {
      const parsed = toFailure(error);
      failure.value = parsed;
      phase.value = "failed";
    } finally {
      controller = null;
      stage.value = null;
    }
  }

  function reset(): void {
    stop();
    phase.value = "idle";
    stage.value = null;
    streamText.value = "";
    outcome.value = null;
    failure.value = null;
    usage.value = EMPTY_USAGE;
  }

  return {
    baseUrl,
    model,
    apiKey,
    configError,
    configured,
    mode,
    draft,
    phase,
    stage,
    streamText,
    outcome,
    failure,
    usage,
    running,
    title,
    stageLabel,
    canSend,
    loadConfig,
    saveEndpoint,
    setMode,
    stop,
    send,
    reset,
  };
});

function toFailure(error: unknown): LlmFailure {
  if (error instanceof SolveError) return error.failure;
  if (error instanceof Error) return { kind: "network", message: error.message };
  return { kind: "network", message: "请求失败" };
}
