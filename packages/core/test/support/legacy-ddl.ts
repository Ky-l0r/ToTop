import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

/**
 * 从 0.8.0 的 Room schema 快照里取建表语句，用来搭一个"旧库"。
 *
 * 为什么读仓库里的快照，而不是在测试里手抄一份 DDL：
 * 手抄的那份迟早和真实结构不一致，而迁移测试一旦建立在抄错的表上，
 * 它验的就不再是迁移，而是抄写。快照是 Room 从实体生成并提交进仓库的，是权威。
 */
const SCHEMA_FILE = fileURLToPath(
  new URL(
    "../../../../app/schemas/com.totop.app.data.local.AppDatabase/10.json",
    import.meta.url,
  ),
);

interface RoomEntity {
  tableName?: string;
  createSql?: string;
}

interface RoomSchemaFile {
  database?: { entities?: RoomEntity[] };
}

export function legacyCreateStatements(tables: readonly string[]): string[] {
  const parsed = JSON.parse(readFileSync(SCHEMA_FILE, "utf8")) as RoomSchemaFile;
  const entities = parsed.database?.entities ?? [];
  const wanted = new Set(tables);
  const statements: string[] = [];
  for (const entity of entities) {
    const name = entity.tableName ?? "";
    const sql = entity.createSql ?? "";
    if (!wanted.has(name) || !sql) continue;
    statements.push(sql.replace(/\$\{TABLE_NAME\}/g, name));
  }
  const missing = tables.filter((table) => !statements.some((sql) => sql.includes(`\`${table}\``)));
  if (missing.length > 0) {
    throw new Error(`0.8.0 快照里找不到这些表：${missing.join(", ")}`);
  }
  return statements;
}
