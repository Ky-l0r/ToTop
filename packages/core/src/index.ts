/**
 * To Top 领域层入口。
 *
 * 这一层只放纯逻辑与数据契约：库结构与迁移、四阶段编排、错因分类、题型判定、改写自洽校验。
 * **不许 import 任何 uni 或平台 API**，否则它就无法在 Node 里单测。
 * 外部依赖（数据库、网络）都由调用方注入。
 */
export const CORE_VERSION = "1.0.0";

// ---------- 数据层 ----------
export type { SqlExecutor, SqlValue } from "./db/executor";
export { SCHEMA_STATEMENTS, SCHEMA_VERSION } from "./db/schema";
export { ensureSchema, readSchemaVersion } from "./db/migrate";
export {
  importLegacyDb,
  looksLikeLegacyDb,
  LegacyImportError,
  type ImportOptions,
  type ImportReport,
} from "./db/legacy";
export { SETTINGS_KEYS, SettingsRepo, type SettingKey } from "./repo/settings";
export { bindParams, quoteSqlValue } from "./db/sql-text";

// ---------- 模型调用 ----------
export type {
  ChatMessage,
  LlmChunk,
  LlmEndpoint,
  LlmFailure,
  LlmFailureKind,
  LlmRequest,
  LlmTransport,
  TokenUsage,
} from "./llm/types";
export { addUsage, EMPTY_USAGE } from "./llm/types";
export { SseParser, type SseEvent } from "./llm/sse";
export {
  buildChatBody,
  chatCompletionsUrl,
  parseCompletion,
  toChunk,
  DONE_MARKER,
  type CompletionResult,
} from "./llm/openai";
export { createFetchTransport, type FetchTransportOptions } from "./llm/fetch-transport";
export { abortedFailure, classifyHttpFailure, networkFailure } from "./llm/errors";
export { backoffMs, MAX_ATTEMPTS, shouldRetry } from "./llm/retry";

// ---------- 图片 ----------
export {
  IMAGE_PROFILE_LEAN,
  IMAGE_PROFILE_STANDARD,
  pickProfile,
  planResize,
  type ImageProfile,
  type ImageSize,
  type ResizePlan,
} from "./image/plan";
export { base64ToBytes, sha256OfBase64, sha256OfText } from "./image/sha256";

// ---------- 解题链路 ----------
export { extractJson, stripFence } from "./solve/json";
export {
  ERROR_TYPES,
  fallbackTitle,
  KP_ID_PATTERN,
  parseErrorReport,
  parseKpointMatches,
  parseQuestionSpec,
  parseStandardSolution,
  SUBJECTS,
  type ChoiceType,
  type ErrorReportSpec,
  type ErrorType,
  type FirstErrorSpec,
  type KpointMatch,
  type KpointMatchSpec,
  type Parsed,
  type ProgressItem,
  type QuestionSpec,
  type SolutionStep,
  type StandardSolutionSpec,
  type StudentStepSpec,
  type Subject,
  type SummaryCardSpec,
} from "./solve/contracts";
export {
  buildSolvePayload,
  runSolve,
  SolveError,
  type SolveDeps,
  type SolveEvent,
  type SolveInput,
  type SolveOutcome,
  type Stage,
} from "./solve/orchestrator";
export type { PromptSet } from "./solve/prompts";
