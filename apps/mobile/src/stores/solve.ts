import { createTransport, defaultTransportMode } from "@/platform/net/transport";
import { getDatabase } from "@/services/db";
import { AUX_PROMPTS, PROMPTS } from "@/solve/prompts";
import { useLlmStore } from "@/stores/llm";
import {
  MAX_PAGES,
  pickImages,
  prepareImage,
  resolveProfile,
  type PreparedImage,
} from "@/platform/image";
import {
  applyRewrite,
  checkRewrite,
  EMPTY_USAGE,
  judgeChoice,
  markOptions,
  parseMaterialDraft,
  revertRewrite,
  runSingleCall,
  runSolve,
  saveSolvedQuestion,
  SingleCallError,
  SolveError,
  type ChoiceVerdict,
  type LlmFailure,
  type OptionItem,
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
  // 模型端点与 Key 由 llm store 统一持有：设置页与这一页改的是同一份值。
  const llm = useLlmStore();

  // ---------- 输入 ----------
  const mode = ref<TeachingMode>("GUIDED");
  const draft = ref("");
  /** 同一道题的多页图片。多页会被当成同一道题，这是 0.8.0 定下的口径。 */
  const images = ref<PreparedImage[]>([]);
  const preparing = ref(false);
  const imageError = ref("");

  // ---------- 运行 ----------
  const phase = ref<SolvePhase>("idle");
  const stage = ref<Stage | null>(null);
  const streamText = ref("");
  const outcome = ref<SolveOutcome | null>(null);
  const failure = ref<LlmFailure | null>(null);
  const usage = ref<TokenUsage>(EMPTY_USAGE);

  /** 落库结果。存不下时不能把已经算出来的解答丢掉，所以只提示、不清屏。 */
  const savedMistakeId = ref("");
  const saveError = ref("");

  // ---------- 改写 ----------
  const rewriting = ref(false);
  /** 改写后的提示，成功与拒绝都写在这里（拒绝时学生要知道为什么没改）。 */
  const rewriteNote = ref("");
  /** 改写前的解答，供当场回退显示用；真正的旧版在库里。 */
  const previousSolution = ref<{ steps: SolveOutcome["solution"]["steps"]; finalAnswer: string } | null>(
    null,
  );

  let controller: AbortController | null = null;

  /** 学生确认过"这题确实多选"之后，同一条解答不再反复追问。 */
  const choiceConfirmed = ref(false);

  const running = computed(() => phase.value === "running");
  const title = computed(() => outcome.value?.spec.title ?? "拍题");

  /** 选择题自洽判定：不拦截答案，只决定要不要问一句。 */
  const choiceVerdict = computed<ChoiceVerdict | null>(() => {
    const current = outcome.value;
    if (!current) return null;
    return judgeChoice(
      current.spec.choiceType,
      current.spec.options,
      current.solution.finalAnswer,
    );
  });

  const optionItems = computed<OptionItem[]>(() => {
    const current = outcome.value;
    if (!current) return [];
    const verdict = choiceVerdict.value;
    const letters = verdict && "letters" in verdict ? verdict.letters : [];
    return markOptions(current.spec.options, letters);
  });

  /** 单选题型却给了多个字母：问一次，不擅自截断。 */
  const showChoiceConfirm = computed(
    () => !choiceConfirmed.value && choiceVerdict.value?.kind === "confirm_multi",
  );

  /** 多选只给了一个字母，或答案里找不到字母：提示核对，不改答案。 */
  const choiceWarning = computed(() => {
    const verdict = choiceVerdict.value;
    if (!verdict) return "";
    if (verdict.kind === "under_count") {
      return "这题标的是多选，但答案只给了一个选项，自己核对一下";
    }
    if (verdict.kind === "unparsed") {
      return "没能从答案里认出选项，可能这题不是选择题";
    }
    return "";
  });

  function confirmChoice(): void {
    choiceConfirmed.value = true;
  }
  const stageLabel = computed(() => {
    if (phase.value === "running") return stage.value ? STAGE_LABEL[stage.value] : "正在开始";
    if (phase.value === "done") return "解完了";
    return "";
  });
  /** 配置是否齐（地址 / 模型名 / Key）。缺一项就不能发。 */
  const configured = computed(() => llm.configured);

  const canSend = computed(
    () =>
      !running.value &&
      !preparing.value &&
      llm.configured &&
      (draft.value.trim() !== "" || images.value.length > 0),
  );

  function setMode(next: TeachingMode): void {
    mode.value = next;
  }

  /**
   * 加一张或多张图。
   *
   * 按 sha256 去重：学生常常把同一页拍两次，发给模型两张一样的图既费 token，
   * 也会让阶段 A 以为是两个不同的页面。
   */
  async function addImages(source: "camera" | "album"): Promise<void> {
    if (preparing.value || running.value) return;
    imageError.value = "";
    preparing.value = true;
    try {
      const paths = await pickImages({ source, remaining: MAX_PAGES - images.value.length });
      const profile = resolveProfile();
      for (const path of paths) {
        const prepared = await prepareImage(path, profile);
        if (images.value.some((item) => item.sha256 === prepared.sha256)) continue;
        if (images.value.length >= MAX_PAGES) break;
        images.value.push(prepared);
      }
    } catch (error) {
      imageError.value = error instanceof Error ? error.message : "图片处理失败";
    } finally {
      preparing.value = false;
    }
  }

  function removeImage(index: number): void {
    images.value.splice(index, 1);
  }

  function stop(): void {
    controller?.abort();
  }

  /**
   * 改错：导入一份标准答案，用它**整段替换**这道题的解答过程。
   *
   * 三个入口共用一套规则（导入标准答案 / 解答卡改错 / 对话订正），这里是最朴素的那个：
   * 拍一张答案 → 解析成结构化 → 自洽校验 → 落库。
   * 校验不过就不写库，只在气泡上说清原因——绝不拿旧步骤配新答案。
   */
  async function rewriteFromImages(source: "camera" | "album"): Promise<void> {
    if (!outcome.value || !savedMistakeId.value || rewriting.value) return;
    rewriting.value = true;
    rewriteNote.value = "";
    try {
      const paths = await pickImages({ source, remaining: 2 });
      if (paths.length === 0) return;

      const profile = resolveProfile();
      const dataUrls: string[] = [];
      for (const path of paths) {
        const prepared = await prepareImage(path, profile);
        dataUrls.push(prepared.dataUrl);
      }

      const text = await runSingleCall({
        prompt: AUX_PROMPTS.materialImport,
        userText: "（学生没有写备注）",
        images: dataUrls,
        transport: createTransport(defaultTransportMode(), llm.endpoint),
        model: llm.model.trim(),
        signal: new AbortController().signal,
        jsonMode: true,
        temperature: 0.1,
      });

      const parsed = parseMaterialDraft(text);
      if (!parsed.ok) {
        rewriteNote.value = `没读出这份答案：${parsed.reason}`;
        return;
      }

      const checked = checkRewrite({
        steps: parsed.value.steps,
        finalAnswer: parsed.value.answer,
        options: outcome.value.spec.options,
        choiceType: outcome.value.spec.choiceType,
      });
      if (!checked.ok) {
        rewriteNote.value = checked.message;
        return;
      }

      const { db } = await getDatabase();
      const result = await applyRewrite(db, {
        mistakeId: savedMistakeId.value,
        steps: checked.steps,
        finalAnswer: checked.finalAnswer,
        reason: "standard_answer",
        nowUtc: Date.now(),
      });

      if (!result.ok) {
        rewriteNote.value =
          result.reason === "not_found"
            ? "这道题没有存进错题本，改写无处可写。先把解答留着，等写库成功再改。"
            : "这份答案和现有过程一样，没有改动。";
        return;
      }

      previousSolution.value = {
        steps: outcome.value.solution.steps,
        finalAnswer: outcome.value.solution.finalAnswer,
      };
      outcome.value = {
        ...outcome.value,
        solution: {
          ...outcome.value.solution,
          steps: checked.steps,
          finalAnswer: checked.finalAnswer,
        },
      };
      choiceConfirmed.value = false;
      rewriteNote.value = "已改写这道题的解答过程";
    } catch (error) {
      const failure = error instanceof SingleCallError ? error.failure : null;
      rewriteNote.value = failure ? failure.message : "这次没改成，可以再试一次";
    } finally {
      rewriting.value = false;
    }
  }

  /** 回退到上一版：界面与库同时回到旧版，不留半套。 */
  async function undoRewrite(): Promise<void> {
    if (!outcome.value || !savedMistakeId.value || !previousSolution.value) return;
    try {
      const { db } = await getDatabase();
      const result = await revertRewrite(db, savedMistakeId.value);
      if (!result.ok) {
        rewriteNote.value = "没有可回退的旧版";
        return;
      }
      outcome.value = {
        ...outcome.value,
        solution: {
          ...outcome.value.solution,
          steps: previousSolution.value.steps,
          finalAnswer: previousSolution.value.finalAnswer,
        },
      };
      previousSolution.value = null;
      rewriteNote.value = "已回到改写前的版本";
    } catch {
      rewriteNote.value = "回退失败，稍后再试";
    }
  }

  /**
   * 把这次解答写进库。
   *
   * 失败不抛：解答已经算出来了，因为写库失败把整屏清掉是最差的选择。
   * 页面上给一行提示，学生至少还能看、还能截图。
   */
  async function persist(result: SolveOutcome): Promise<void> {
    saveError.value = "";
    savedMistakeId.value = "";
    try {
      const { db } = await getDatabase();
      const saved = await saveSolvedQuestion(db, {
        spec: result.spec,
        solution: result.solution,
        report: result.report,
        kpoints: result.kpoints,
        mode: mode.value,
        nowUtc: Date.now(),
        imagePath: images.value[0]?.path ?? null,
        imageSha256: images.value[0]?.sha256 ?? null,
      });
      savedMistakeId.value = saved.mistakeId;
    } catch (error) {
      const detail = error instanceof Error ? error.message : "本地库不可用";
      saveError.value = `解答算出来了，但没存进错题本：${detail}`;
    }
  }

  async function send(): Promise<void> {
    if (!canSend.value) return;

    phase.value = "running";
    stage.value = "A";
    streamText.value = "";
    outcome.value = null;
    failure.value = null;
    usage.value = EMPTY_USAGE;
    choiceConfirmed.value = false;

    controller = new AbortController();
    try {
      const result = await runSolve(
        {
          images: images.value.map((item) => item.dataUrl),
          studentText: draft.value.trim(),
          mode: mode.value,
          // 知识点候选要等知识点仓储（T10）。为空时阶段 D 直接跳过，不浪费一次请求。
          candidateKpoints: [],
          signal: controller.signal,
        },
        {
          transport: createTransport(defaultTransportMode(), llm.endpoint),
          prompts: PROMPTS,
          model: llm.model.trim(),
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
      await persist(result);
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
    choiceConfirmed.value = false;
    savedMistakeId.value = "";
    saveError.value = "";
  }

  return {
    configured,
    mode,
    draft,
    images,
    preparing,
    imageError,
    phase,
    stage,
    streamText,
    outcome,
    failure,
    usage,
    savedMistakeId,
    saveError,
    rewriting,
    rewriteNote,
    previousSolution,
    running,
    title,
    stageLabel,
    choiceVerdict,
    optionItems,
    showChoiceConfirm,
    choiceWarning,
    confirmChoice,
    canSend,
    setMode,
    addImages,
    removeImage,
    stop,
    send,
    reset,
    rewriteFromImages,
    undoRewrite,
  };
});

function toFailure(error: unknown): LlmFailure {
  if (error instanceof SolveError) return error.failure;
  if (error instanceof Error) return { kind: "network", message: error.message };
  return { kind: "network", message: "请求失败" };
}
