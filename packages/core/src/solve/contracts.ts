import { extractJson } from "./json";
import { resolveQuestionType } from "./question-type";

/**
 * 四个阶段的输出契约与校验。
 *
 * 校验的尺度：**结构不对就整条失败，内容不对就降级并记一笔**。
 * - 缺 stem、steps 为空、JSON 没闭合：这条回答没法用，失败让用户重试；
 * - 错因写了个不在七类里的词、知识点 ID 不合法、标题缺失：能救，救完记进 notes。
 * 一刀切地「失败」会让一次措辞失误重跑整条链路，一刀切地「容忍」会把脏数据写进库。
 */

export const SUBJECTS = [
  "chinese",
  "math",
  "english",
  "physics",
  "chemistry",
  "biology",
  "politics",
  "history",
  "geography",
  "technology",
  "general",
] as const;
export type Subject = (typeof SUBJECTS)[number];

/** 知识点 ID 是受控的，AI 不得自造。 */
export const KP_ID_PATTERN = /^KP_[A-Z]{2,12}_[0-9]{4}$/;

/** 错因固定七类，多一个都不收。 */
export const ERROR_TYPES = [
  "审题理解",
  "概念混淆",
  "公式误用",
  "计算失误",
  "方法选择不当",
  "步骤缺失",
  "书写规范",
] as const;
export type ErrorType = (typeof ERROR_TYPES)[number];

export type ChoiceType = "single" | "multi" | "none" | "unknown";
export type HandwritingLevel = "none" | "partial" | "full";
export type ProgressState = "done" | "stuck" | "missing";

export interface ProgressItem {
  no: number | null;
  state: ProgressState;
}

export interface QuestionSpec {
  subject: Subject;
  grade: string;
  /** 一句话概括，用作会话标题；缺失时用题干兜底。 */
  title: string;
  /** 学科 × 题型，受控枚举；判不出来时取该科最常见的一档并记进 notes。 */
  questionType: string;
  stem: string;
  known: string[];
  ask: string;
  options: string[];
  choiceType: ChoiceType;
  hasFigure: boolean;
  stemLatex: string;
  handwriting: HandwritingLevel;
  handwritingText: string;
  progress: ProgressItem[];
  warnings: string[];
}

export interface SolutionStep {
  idx: number;
  content: string;
  why: string;
}

export interface StandardSolutionSpec {
  steps: SolutionStep[];
  finalAnswer: string;
  method: string;
  check: string;
  confidence: number;
}

export interface StudentStepSpec {
  idx: number;
  content: string;
  status: "correct" | "wrong" | "missing";
  alignTo: number | null;
}

export interface FirstErrorSpec {
  stepIdx: number;
  subQuestion: number | null;
  type: ErrorType;
  explain: string;
  correctThinking: string;
}

export interface SummaryCardSpec {
  title: string;
  bullets: string[];
}

export interface ErrorReportSpec {
  studentSteps: StudentStepSpec[];
  firstError: FirstErrorSpec | null;
  summaryCard: SummaryCardSpec;
}

export interface KpointMatch {
  id: string;
  name: string;
  confidence: number;
  usage: string;
}

export interface KpointMatchSpec {
  kpoints: KpointMatch[];
  primaryId: string | null;
  unmatched: boolean;
}

/** 解析结果。notes 记录"降级处理过什么"，要写进留痕，不许只留在内存里。 */
export type Parsed<T> =
  | { ok: true; value: T; notes: string[] }
  | { ok: false; reason: string };

class ContractError extends Error {}

function fail(message: string): never {
  throw new ContractError(message);
}

function readObject(raw: string): Record<string, unknown> {
  const extracted = extractJson(raw);
  if (!extracted.ok || typeof extracted.value !== "object" || extracted.value === null) {
    fail(extracted.reason ?? "模型返回的不是对象");
  }
  return extracted.value as Record<string, unknown>;
}

function asString(source: Record<string, unknown>, key: string, field: string): string {
  const value = source[key];
  if (typeof value !== "string") fail(`${field} 不是字符串`);
  return value;
}

function asOptionalString(source: Record<string, unknown>, key: string, fallback = ""): string {
  const value = source[key];
  return typeof value === "string" ? value : fallback;
}

function asStringArray(source: Record<string, unknown>, key: string, field: string): string[] {
  const value = source[key];
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) fail(`${field} 不是数组`);
  return value.filter((item): item is string => typeof item === "string");
}

function asOptionalNumber(source: Record<string, unknown>, key: string): number | null {
  const value = source[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function asBoolean(source: Record<string, unknown>, key: string, fallback = false): boolean {
  const value = source[key];
  return typeof value === "boolean" ? value : fallback;
}

function oneOf<T extends string>(
  value: string,
  allowed: readonly T[],
  field: string,
): T {
  if (!(allowed as readonly string[]).includes(value)) {
    fail(`${field} 取值不在允许范围内：${value}`);
  }
  return value as T;
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value));
}

/** 标题兜底：取题干前 12 个字，去掉换行与多余空白。 */
export function fallbackTitle(stem: string): string {
  const flat = stem.replace(/\s+/g, " ").trim();
  if (flat.length <= 12) return flat;
  return flat.slice(0, 12) + "…";
}

export function parseQuestionSpec(raw: string): Parsed<QuestionSpec> {
  try {
    const obj = readObject(raw);
    const notes: string[] = [];

    const subjectRaw = asString(obj, "subject", "subject");
    const subject = (SUBJECTS as readonly string[]).includes(subjectRaw)
      ? (subjectRaw as Subject)
      : "general";
    if (subject !== subjectRaw) notes.push(`subject 无法识别（${subjectRaw}），按 general 处理`);

    const stem = asString(obj, "stem", "stem").trim();
    if (stem === "") fail("题干为空");

    const titleRaw = asOptionalString(obj, "title").trim();
    const title = titleRaw === "" ? fallbackTitle(stem) : titleRaw;
    if (titleRaw === "") notes.push("title 缺失，用题干前 12 字兜底");

    const questionTypeRaw = asOptionalString(obj, "questionType").trim();
    const resolvedType = resolveQuestionType(subject, questionTypeRaw);
    if (resolvedType.guessed) {
      notes.push(
        `题型无法识别（${questionTypeRaw || "空"}），按「${resolvedType.type}」处理`,
      );
    }

    const choiceType = oneOf(
      asOptionalString(obj, "choiceType", "unknown"),
      ["single", "multi", "none", "unknown"] as const,
      "choiceType",
    );

    const progressRaw = obj["progress"];
    const progress: ProgressItem[] = [];
    if (Array.isArray(progressRaw)) {
      for (const item of progressRaw) {
        if (typeof item !== "object" || item === null) continue;
        const entry = item as Record<string, unknown>;
        const stateRaw = asOptionalString(entry, "state", "");
        if (stateRaw !== "done" && stateRaw !== "stuck" && stateRaw !== "missing") continue;
        progress.push({ no: asOptionalNumber(entry, "no"), state: stateRaw });
      }
    }

    const ask = asString(obj, "ask", "ask").trim();
    if (ask === "") fail("ask 为空");

    return {
      ok: true,
      notes,
      value: {
        subject,
        grade: asOptionalString(obj, "grade", "未知"),
        title,
        questionType: resolvedType.type,
        stem,
        known: asStringArray(obj, "known", "known"),
        ask,
        options: asStringArray(obj, "options", "options"),
        choiceType,
        hasFigure: asBoolean(obj, "hasFigure"),
        stemLatex: asOptionalString(obj, "stemLatex"),
        handwriting: oneOf(
          asOptionalString(obj, "handwriting", "none"),
          ["none", "partial", "full"] as const,
          "handwriting",
        ),
        handwritingText: asOptionalString(obj, "handwritingText"),
        progress,
        warnings: asStringArray(obj, "warnings", "warnings"),
      },
    };
  } catch (error) {
    return { ok: false, reason: describe(error) };
  }
}

export function parseStandardSolution(raw: string): Parsed<StandardSolutionSpec> {
  try {
    const obj = readObject(raw);
    const notes: string[] = [];

    const stepsRaw = obj["steps"];
    if (!Array.isArray(stepsRaw) || stepsRaw.length === 0) fail("steps 为空");
    const steps: SolutionStep[] = [];
    for (const item of stepsRaw) {
      if (typeof item !== "object" || item === null) continue;
      const entry = item as Record<string, unknown>;
      const content = asOptionalString(entry, "content").trim();
      if (content === "") continue;
      steps.push({
        idx: asOptionalNumber(entry, "idx") ?? steps.length + 1,
        content,
        why: asOptionalString(entry, "why"),
      });
    }
    if (steps.length === 0) fail("steps 里没有可用内容");
    if (steps.length < 2) notes.push("steps 只有 1 步，可能是模型漏写");

    const finalAnswer = asString(obj, "finalAnswer", "finalAnswer").trim();
    if (finalAnswer === "") fail("finalAnswer 为空");

    return {
      ok: true,
      notes,
      value: {
        steps,
        finalAnswer,
        method: asOptionalString(obj, "method"),
        check: asOptionalString(obj, "check"),
        confidence: clamp01(asOptionalNumber(obj, "confidence") ?? 0),
      },
    };
  } catch (error) {
    return { ok: false, reason: describe(error) };
  }
}

export function parseErrorReport(raw: string): Parsed<ErrorReportSpec> {
  try {
    const obj = readObject(raw);
    const notes: string[] = [];

    const studentSteps: StudentStepSpec[] = [];
    const stepsRaw = obj["studentSteps"];
    if (Array.isArray(stepsRaw)) {
      for (const item of stepsRaw) {
        if (typeof item !== "object" || item === null) continue;
        const entry = item as Record<string, unknown>;
        const statusRaw = asOptionalString(entry, "status", "");
        if (statusRaw !== "correct" && statusRaw !== "wrong" && statusRaw !== "missing") continue;
        const content = asOptionalString(entry, "content").trim();
        if (content === "") continue;
        studentSteps.push({
          idx: asOptionalNumber(entry, "idx") ?? studentSteps.length + 1,
          content,
          status: statusRaw,
          alignTo: asOptionalNumber(entry, "alignTo"),
        });
      }
    }

    let firstError: FirstErrorSpec | null = null;
    const firstRaw = obj["firstError"];
    if (firstRaw && typeof firstRaw === "object") {
      const entry = firstRaw as Record<string, unknown>;
      const typeRaw = asOptionalString(entry, "type", "");
      if (!(ERROR_TYPES as readonly string[]).includes(typeRaw)) {
        // 错因是受控七类。模型写了个新词，说明它对错因的理解跑偏了，
        // 这一条不能收——但整份解答仍然有用，所以降级为「没定位到错因」并记一笔。
        notes.push(`错因不在七类内（${typeRaw || "空"}），本份解答不标错因`);
      } else {
        const explain = asOptionalString(entry, "explain").trim();
        if (explain === "") {
          notes.push("错因说明为空，本份解答不标错因");
        } else {
          firstError = {
            stepIdx: asOptionalNumber(entry, "stepIdx") ?? 1,
            subQuestion: asOptionalNumber(entry, "subQuestion"),
            type: typeRaw as ErrorType,
            explain,
            correctThinking: asOptionalString(entry, "correctThinking"),
          };
        }
      }
    }

    const cardRaw = obj["summaryCard"];
    const card = cardRaw && typeof cardRaw === "object" ? (cardRaw as Record<string, unknown>) : {};
    const summaryCard: SummaryCardSpec = {
      title: asOptionalString(card, "title", "这题的要点"),
      bullets: asStringArray(card, "bullets", "summaryCard.bullets"),
    };

    return { ok: true, notes, value: { studentSteps, firstError, summaryCard } };
  } catch (error) {
    return { ok: false, reason: describe(error) };
  }
}

export function parseKpointMatches(raw: string): Parsed<KpointMatchSpec> {
  try {
    const obj = readObject(raw);
    const notes: string[] = [];

    const kpoints: KpointMatch[] = [];
    const listRaw = obj["kpoints"];
    if (Array.isArray(listRaw)) {
      for (const item of listRaw) {
        if (typeof item !== "object" || item === null) continue;
        const entry = item as Record<string, unknown>;
        const id = asOptionalString(entry, "id").trim();
        if (!KP_ID_PATTERN.test(id)) {
          notes.push(`知识点 ID 不合法，已丢弃：${id || "空"}`);
          continue;
        }
        kpoints.push({
          id,
          name: asOptionalString(entry, "name", id),
          confidence: clamp01(asOptionalNumber(entry, "confidence") ?? 0),
          usage: asOptionalString(entry, "usage"),
        });
        if (kpoints.length >= 3) break;
      }
    }

    const primaryRaw = asOptionalString(obj, "primaryId").trim();
    let primaryId: string | null = primaryRaw === "" ? null : primaryRaw;
    if (primaryId !== null && !kpoints.some((kpoint) => kpoint.id === primaryId)) {
      notes.push("primaryId 不在返回的知识点列表里，已置空");
      primaryId = null;
    }

    return {
      ok: true,
      notes,
      value: { kpoints, primaryId, unmatched: asBoolean(obj, "unmatched", kpoints.length === 0) },
    };
  } catch (error) {
    return { ok: false, reason: describe(error) };
  }
}

function describe(error: unknown): string {
  if (error instanceof ContractError) return error.message;
  if (error instanceof Error) return error.message;
  return "输出格式不正确";
}
