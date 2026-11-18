import { extractJson } from "./json";
import { SUBJECTS, type SolutionStep, type Subject } from "./contracts";

/**
 * 标准答案导入的契约。
 *
 * 这份资料是"老师怎么讲的"，不是"AI 怎么解的"——所以 answer 与 steps 都要贴原文，
 * 解析器不改写、不补全。看不清的部分进 warnings，由学生自己判断要不要重拍。
 */
export interface MaterialDraft {
  subject: Subject;
  grade: string;
  title: string;
  stem: string;
  answer: string;
  steps: SolutionStep[];
  pitfalls: string[];
  warnings: string[];
}

export type MaterialParseResult =
  | { ok: true; value: MaterialDraft; notes: string[] }
  | { ok: false; reason: string };

export function parseMaterialDraft(raw: string): MaterialParseResult {
  const extracted = extractJson(raw);
  if (!extracted.ok || typeof extracted.value !== "object" || extracted.value === null) {
    return { ok: false, reason: extracted.reason ?? "模型返回的不是对象" };
  }
  const obj = extracted.value as Record<string, unknown>;
  const notes: string[] = [];

  const stem = str(obj["stem"]).trim();
  if (stem === "") return { ok: false, reason: "没读出题干" };

  const subjectRaw = str(obj["subject"]);
  const subject = (SUBJECTS as readonly string[]).includes(subjectRaw)
    ? (subjectRaw as Subject)
    : "general";
  if (subject !== subjectRaw) notes.push(`学科无法识别（${subjectRaw || "空"}），按 general 处理`);

  const titleRaw = str(obj["title"]).trim();
  const title = titleRaw === "" ? shorten(stem) : titleRaw;
  if (titleRaw === "") notes.push("标题缺失，用题干前 12 字兜底");

  const answer = str(obj["answer"]).trim();
  if (answer === "") notes.push("这份资料里没有答案，只存了题干与解析");

  const steps: SolutionStep[] = [];
  if (Array.isArray(obj["steps"])) {
    for (const item of obj["steps"] as unknown[]) {
      if (typeof item !== "object" || item === null) continue;
      const entry = item as Record<string, unknown>;
      const content = str(entry["content"]).trim();
      if (content === "") continue;
      steps.push({
        idx: Number(entry["idx"]) || steps.length + 1,
        content,
        why: str(entry["why"]),
      });
    }
  }

  return {
    ok: true,
    notes,
    value: {
      subject,
      grade: str(obj["grade"]).trim() || "未知",
      title,
      stem,
      answer,
      steps,
      pitfalls: strArray(obj["pitfalls"]),
      warnings: strArray(obj["warnings"]),
    },
  };
}

/** 导入时给模型的用户消息：图片 + 可选备注。 */
export function buildMaterialUserText(note: string): string {
  const trimmed = note.trim();
  return trimmed === "" ? "（学生没有写备注）" : trimmed;
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function strArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string" && item.trim() !== "");
}

function shorten(stem: string): string {
  const flat = stem.replace(/\s+/g, " ").trim();
  return flat.length <= 12 ? flat : `${flat.slice(0, 12)}…`;
}
