import type { SqlValue } from "./executor";

/**
 * 把参数拼进 SQL 文本。
 *
 * 为什么领域层要管这件事：端上的 plus.sqlite 没有参数绑定，只有一句完整 SQL。
 * 转义逻辑放在纯函数里，就能在 Node 单测里穷举引号、换行、NULL 这些情况，
 * 而不是等真机上某条带单引号的题干把语句搞崩。
 *
 * 两条约束：
 * 1. 字符串里的单引号翻倍（SQL 标准做法），不做反斜杠转义；
 * 2. 语句里除了占位符，不允许出现别的 `?`——出现就会错位。
 */
export function quoteSqlValue(value: SqlValue): string {
  if (value === null) return "NULL";
  if (typeof value === "number") return Number.isFinite(value) ? String(value) : "NULL";
  return `'${value.replace(/'/g, "''")}'`;
}

export function bindParams(sql: string, params: readonly SqlValue[] = []): string {
  let index = 0;
  return sql.replace(/\?/g, () => {
    if (index >= params.length) {
      throw new Error(`SQL 占位符比参数多：第 ${index + 1} 个占位符没有对应参数`);
    }
    const value = params[index] ?? null;
    index += 1;
    return quoteSqlValue(value);
  });
}
