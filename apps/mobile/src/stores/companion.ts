import { createTransport, defaultTransportMode } from "@/platform/net/transport";
import { AUX_PROMPTS } from "@/solve/prompts";
import { useLlmStore } from "@/stores/llm";
import {
  createId,
  isBlankQuestion,
  runSingleCall,
  SingleCallError,
  trimChat,
  type ChatTurn,
} from "@totop/core";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 陪伴页：嘴上问一句。
 *
 * 这一页没有计时、没有拍照、没有示例问句——那三样都是 0.8.0 加的，都不属于"随口问一句"。
 * 失败时**在原气泡里重发**，不新增气泡：同一句问话在列表里出现两次，
 * 学生根本分不清哪条是失败的。
 */
export interface Bubble {
  id: string;
  role: "user" | "assistant";
  content: string;
  /** 助手气泡对应的提问，重试时复用。 */
  question?: string;
  pending?: boolean;
  failed?: boolean;
}

export const useCompanionStore = defineStore("companion", () => {
  const bubbles = ref<Bubble[]>([]);
  const draft = ref("");
  const sending = ref(false);

  const canSend = computed(() => !sending.value && !isBlankQuestion(draft.value));

  /** 送进模型的历史：去掉失败与待答的气泡，只留有内容的问答。 */
  const history = computed<ChatTurn[]>(() =>
    trimChat(
      bubbles.value
        .filter((bubble) => !bubble.pending && !bubble.failed && bubble.content.trim() !== "")
        .map((bubble) => ({ role: bubble.role, content: bubble.content })),
    ),
  );

  async function send(text?: string): Promise<void> {
    const question = (text ?? draft.value).trim();
    if (sending.value || isBlankQuestion(question)) return;

    const assistantId = createId("b", Date.now());
    bubbles.value.push({ id: createId("b", Date.now()), role: "user", content: question });
    bubbles.value.push({ id: assistantId, role: "assistant", content: "", question, pending: true });
    draft.value = "";
    sending.value = true;

    try {
      await answer(assistantId, question);
    } finally {
      sending.value = false;
    }
  }

  /** 重试这一问：复用原气泡，不新增。 */
  async function retry(bubbleId: string): Promise<void> {
    const bubble = bubbles.value.find((item) => item.id === bubbleId);
    if (!bubble || bubble.role !== "assistant" || !bubble.question || sending.value) return;

    bubble.pending = true;
    bubble.failed = false;
    bubble.content = "";
    sending.value = true;
    try {
      await answer(bubbleId, bubble.question);
    } finally {
      sending.value = false;
    }
  }

  async function answer(bubbleId: string, question: string): Promise<void> {
    const bubble = bubbles.value.find((item) => item.id === bubbleId);
    if (!bubble) return;

    const llm = useLlmStore();
    if (!llm.configured) {
      bubble.pending = false;
      bubble.failed = true;
      bubble.content = "还没配置模型。先去设置里填地址与 Key，再回来问。";
      return;
    }

    try {
      const text = await runSingleCall({
        prompt: AUX_PROMPTS.companionAsk,
        userText: question,
        history: history.value,
        transport: createTransport(defaultTransportMode(), llm.endpoint),
        model: llm.model.trim(),
        signal: new AbortController().signal,
        jsonMode: false,
        temperature: 0.3,
      });
      bubble.content = text.trim() || "这次没答出来，可以重试这一问。";
      bubble.pending = false;
      bubble.failed = bubble.content.trim() === "";
    } catch (error) {
      bubble.pending = false;
      bubble.failed = true;
      bubble.content =
        error instanceof SingleCallError ? error.failure.message : "没连上模型，重试这一问就行。";
    }
  }

  function clear(): void {
    bubbles.value = [];
  }

  return { bubbles, draft, sending, canSend, history, send, retry, clear };
});
