/**
 * 数据库执行口。
 *
 * 领域层只认这个接口，不认 uni 的 plus.sqlite，也不认 node:sqlite：
 * 端上由 apps/mobile 提供实现，测试里用 node:sqlite 提供实现。
 * 这样才能在本机跑真实的建表与迁移，而不是对着 mock 自说自话。
 */
export type SqlValue = string | number | null;

export interface SqlExecutor {
  /** 执行一条或多条不带参数的语句（DDL 用）。 */
  exec(sql: string): Promise<void>;

  /** 执行一条带参数的写语句。 */
  run(sql: string, params?: SqlValue[]): Promise<void>;

  /** 查询多行。 */
  all<T>(sql: string, params?: SqlValue[]): Promise<T[]>;

  /** 事务：回调抛错则整体回滚。 */
  transaction<T>(fn: () => Promise<T>): Promise<T>;
}
