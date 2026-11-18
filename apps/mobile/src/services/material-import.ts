import {
  createMaterial,
  parseMaterialDraft,
  runSingleCall,
  SingleCallError,
  type LlmFailure,
  type MaterialDraft,
} from "@totop/core";
import { MAX_PAGES, pickImages, prepareImage, resolveProfile } from "@/platform/image";
import { createTransport, defaultTransportMode } from "@/platform/net/transport";
import { getDatabase } from "@/services/db";
import { AUX_PROMPTS } from "@/solve/prompts";
import { useLlmStore } from "@/stores/llm";

/**
 * 标准答案导入与"讲一遍"。
 *
 * 两者都是一次调用，不走四阶段：导入要的是"原样整理"，讲一遍要的是"讲清为什么"，
 * 都不需要识题—求解—找错因这一整套。它们与四阶段共用同一个传输、超时与失败分档。
 */

export interface ImportOutcome {
  materialId: string;
  draft: MaterialDraft;
  notes: string[];
}

export class ImportError extends Error {
  constructor(readonly failure: LlmFailure, readonly stage: "pick" | "call" | "parse" | "save") {
    super(failure.message);
    this.name = "ImportError";
  }
}

/** 从相册/相机取一页标准答案，解析成资料卡并落库。 */
export async function importMaterialFromImages(
  source: "camera" | "album",
  note: string,
): Promise<ImportOutcome | null> {
  const llm = useLlmStore();
  if (!llm.configured) {
    throw new ImportError({ kind: "bad_base_url", message: "还没配置模型，先去设置里填地址与 Key" }, "call");
  }

  const paths = await pickImages({ source, remaining: Math.min(MAX_PAGES, 3) });
  if (paths.length === 0) return null; // 用户取消，不算错误

  let dataUrls: string[];
  try {
    const profile = resolveProfile();
    const prepared = [];
    for (const path of paths) prepared.push(await prepareImage(path, profile));
    dataUrls = prepared.map((item) => item.dataUrl);
  } catch (error) {
    const message = error instanceof Error ? error.message : "图片处理失败";
    throw new ImportError({ kind: "network", message }, "pick");
  }

  const controller = new AbortController();
  let text: string;
  try {
    text = await runSingleCall({
      prompt: AUX_PROMPTS.materialImport,
      userText: note.trim() === "" ? "（学生没有写备注）" : note,
      images: dataUrls,
      transport: createTransport(defaultTransportMode(), llm.endpoint),
      model: llm.model.trim(),
      signal: controller.signal,
      jsonMode: true,
      temperature: 0.1,
    });
  } catch (error) {
    throw new ImportError(toFailure(error), "call");
  }

  const parsed = parseMaterialDraft(text);
  if (!parsed.ok) {
    throw new ImportError({ kind: "parse_failed", message: parsed.reason }, "parse");
  }

  try {
    const { db } = await getDatabase();
    const materialId = await createMaterial(db, parsed.value, {
      nowUtc: Date.now(),
      origin: source === "camera" ? "拍照导入" : "相册导入",
    });
    return { materialId, draft: parsed.value, notes: parsed.notes };
  } catch (error) {
    const message = error instanceof Error ? error.message : "写入失败";
    throw new ImportError({ kind: "network", message }, "save");
  }
}

/** 把这份标准答案讲成学生能跟着想一遍的过程。返回纯文本。 */
export async function explainMaterial(input: {
  subject: string;
  grade: string;
  stem: string;
  answer: string;
  steps: Array<{ idx: number; content: string; why: string }>;
  pitfalls: string[];
}): Promise<string> {
  const llm = useLlmStore();
  if (!llm.configured) {
    throw new ImportError({ kind: "bad_base_url", message: "还没配置模型，先去设置里填地址与 Key" }, "call");
  }

  const controller = new AbortController();
  try {
    return await runSingleCall({
      prompt: AUX_PROMPTS.materialExplain,
      userText: JSON.stringify(input),
      transport: createTransport(defaultTransportMode(), llm.endpoint),
      model: llm.model.trim(),
      signal: controller.signal,
      jsonMode: false,
      temperature: 0.3,
    });
  } catch (error) {
    throw new ImportError(toFailure(error), "call");
  }
}

function toFailure(error: unknown): LlmFailure {
  if (error instanceof SingleCallError) return error.failure;
  if (error instanceof Error) return { kind: "network", message: error.message };
  return { kind: "network", message: "请求失败" };
}
