import { bindParams, type SqlExecutor, type SqlValue } from "@totop/core";

/**
 * 端上 SQLite 驱动（App 端 plus.sqlite）。
 *
 * 两个必须知道的事实：
 * 1. **plus.sqlite 没有参数绑定**，只有一句完整 SQL 字符串。
 *    所以这里自己做转义：字符串里的单引号翻倍，数字原样，null 写 NULL。
 *    代价是 SQL 里不能出现字面量的 `?`（我们的语句只有占位符用它）。
 * 2. **事务要自己开**，且不能嵌套。外面套一层 inTx 标记，
 *    内层直接执行，避免 BEGIN 套 BEGIN 直接报错。
 *
 * 这个文件只在 App 端跑，H5 调试时没有 plus，调用方要自己判断平台。
 */

interface PlusSqliteOptions {
  name: string;
  path?: string;
  sql?: string;
  success?: (payload?: unknown) => void;
  fail?: (error?: unknown) => void;
}

interface PlusSqliteApi {
  openDatabase(options: PlusSqliteOptions): void;
  selectSql(options: PlusSqliteOptions): void;
  executeSql(options: PlusSqliteOptions): void;
  closeDatabase(options: PlusSqliteOptions): void;
}

declare const plus: { sqlite: PlusSqliteApi } | undefined;

/** 应用私有目录下的库文件名。 */
export const APP_DB_NAME = "totop";

/** 应用私有目录（uni-app 的 _doc 对应 iOS/Android 的 Documents 沙箱）。 */
const APP_DB_PATH = "_doc/totop.db";

function requirePlus(): PlusSqliteApi {
  if (typeof plus === "undefined" || !plus.sqlite) {
    throw new Error("plus.sqlite 不可用：数据库只在 App 端可用，H5 调试请改用内存实现");
  }
  return plus.sqlite;
}

/** 把参数拼进 SQL。转义与占位符替换的实现放在领域层，那边有单测。 */
export { bindParams, quoteSqlValue } from "@totop/core";

export class PlusSqliteExecutor implements SqlExecutor {
  private inTx = false;

  constructor(private readonly name: string) {}

  async exec(sql: string): Promise<void> {
    await this.execute(sql);
  }

  async run(sql: string, params: readonly SqlValue[] = []): Promise<void> {
    await this.execute(bindParams(sql, params));
  }

  async all<T>(sql: string, params: readonly SqlValue[] = []): Promise<T[]> {
    const api = requirePlus();
    const bound = bindParams(sql, params);
    return await new Promise<T[]>((resolve, reject) => {
      api.selectSql({
        name: this.name,
        sql: bound,
        success: (payload) => resolve((payload as T[] | undefined) ?? []),
        fail: (error) => reject(new Error(`查询失败：${describe(error)}`)),
      });
    });
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    if (this.inTx) return await fn();
    this.inTx = true;
    try {
      await this.execute("BEGIN IMMEDIATE");
      const result = await fn();
      await this.execute("COMMIT");
      return result;
    } catch (error) {
      await this.execute("ROLLBACK").catch(() => undefined);
      throw error;
    } finally {
      this.inTx = false;
    }
  }

  private async execute(sql: string): Promise<void> {
    const api = requirePlus();
    await new Promise<void>((resolve, reject) => {
      api.executeSql({
        name: this.name,
        sql,
        success: () => resolve(),
        fail: (error) => reject(new Error(`执行失败：${describe(error)}`)),
      });
    });
  }
}

function describe(error: unknown): string {
  if (typeof error === "string") return error;
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return "未知错误";
}

/** 打开（或创建）应用库，返回可用的执行器。 */
export async function openAppDatabase(): Promise<SqlExecutor> {
  const api = requirePlus();
  await new Promise<void>((resolve, reject) => {
    api.openDatabase({
      name: APP_DB_NAME,
      path: APP_DB_PATH,
      success: () => resolve(),
      fail: (error) => reject(new Error(`打开数据库失败：${describe(error)}`)),
    });
  });
  return new PlusSqliteExecutor(APP_DB_NAME);
}

/** 以只读方式挂一个旧库（导入 0.8.0 备份时用）。 */
export async function openLegacyDatabase(
  absolutePath: string,
  name = "legacy_import",
): Promise<SqlExecutor> {
  const api = requirePlus();
  await new Promise<void>((resolve, reject) => {
    api.openDatabase({
      name,
      path: absolutePath,
      success: () => resolve(),
      fail: (error) => reject(new Error(`打开旧备份失败：${describe(error)}`)),
    });
  });
  return new PlusSqliteExecutor(name);
}

export function closeDatabase(name: string): void {
  requirePlus().closeDatabase({ name });
}
