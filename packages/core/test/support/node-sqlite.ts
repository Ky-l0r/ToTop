import { DatabaseSync } from "node:sqlite";
import type { SqlExecutor, SqlValue } from "../../src/db/executor";

/**
 * 测试用的真实 SQLite 执行器（Node 22 内置 node:sqlite）。
 *
 * 为什么不用 mock：迁移这件事的正确性全在 SQL 上——列名写错、类型对不上、
 * 事务没回滚，mock 一个都发现不了。这里跑的是真的 SQLite 引擎。
 * 代价是 node:sqlite 目前标着 experimental，启动会打一行警告，不影响结果。
 */
export interface DbHarness {
  db: SqlExecutor;
  close(): void;
}

export function createHarness(): DbHarness {
  const raw = new DatabaseSync(":memory:");
  let depth = 0;

  const db: SqlExecutor = {
    async exec(sql: string): Promise<void> {
      raw.exec(sql);
    },
    async run(sql: string, params: readonly SqlValue[] = []): Promise<void> {
      raw.prepare(sql).run(...params);
    },
    async all<T>(sql: string, params: readonly SqlValue[] = []): Promise<T[]> {
      return raw.prepare(sql).all(...params) as T[];
    },
    async transaction<T>(fn: () => Promise<T>): Promise<T> {
      if (depth > 0) return await fn();
      depth += 1;
      raw.exec("BEGIN");
      try {
        const result = await fn();
        raw.exec("COMMIT");
        return result;
      } catch (error) {
        raw.exec("ROLLBACK");
        throw error;
      } finally {
        depth -= 1;
      }
    },
  };

  return { db, close: () => raw.close() };
}
