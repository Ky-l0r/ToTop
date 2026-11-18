/**
 * To Top 领域层入口。
 *
 * 这一层只放纯逻辑与数据契约：掌握度、错因分类、题型判定、改写自洽校验、库结构与迁移。
 * **不许 import 任何 uni 或平台 API**，否则它就无法在 Node 里单测。
 * 数据库驱动由调用方注入（见 db/executor.ts 的 SqlExecutor）：
 * 端上用 plus.sqlite，测试里用 node:sqlite，领域层两边都不认识。
 */
export const CORE_VERSION = "1.0.0";

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
