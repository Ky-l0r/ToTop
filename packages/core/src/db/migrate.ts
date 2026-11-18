import { SCHEMA_STATEMENTS, SCHEMA_VERSION } from "./schema";
import type { SqlExecutor } from "./executor";

/** 当前库版本（PRAGMA user_version）。空库返回 0。 */
export async function readSchemaVersion(db: SqlExecutor): Promise<number> {
  const rows = await db.all<{ user_version: number }>("PRAGMA user_version");
  return rows[0]?.user_version ?? 0;
}

/**
 * 增量迁移。
 *
 * `CREATE TABLE IF NOT EXISTS` 只能建出新表，**改不了已有的表**：
 * 装过 alpha 的设备上 material 表已经在，缺一列时建表语句什么都不会做，
 * 于是查询在运行时报 "no such column"。这类问题只在升级过的设备上出现，
 * 表现为"资料库打不开"，是最难复现的一种。
 *
 * 约定：每一步只做一件可重复执行的事（加列前先查 PRAGMA），失败即中止。
 */
interface Migration {
  /** 目标版本号：从 `version - 1` 升到 `version`。 */
  version: number;
  apply: (db: SqlExecutor) => Promise<void>;
}

const MIGRATIONS: readonly Migration[] = [
  {
    version: 2,
    apply: async (db) => {
      await ensureColumn(db, "material", "explanation", "TEXT NOT NULL DEFAULT ''");
      await ensureColumn(db, "material", "stem", "TEXT NOT NULL DEFAULT ''");
    },
  },
];

async function ensureColumn(
  db: SqlExecutor,
  table: string,
  column: string,
  definition: string,
): Promise<void> {
  const rows = await db.all<{ name: string }>(`PRAGMA table_info(${table})`);
  if (rows.some((row) => row.name === column)) return;
  await db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
}

/**
 * 建表并把库升到当前版本。可重复调用。
 *
 * 顺序：先建缺失的表（新库一步到位），再按版本补列（老库补齐），最后写版本号。
 */
export async function ensureSchema(db: SqlExecutor): Promise<number> {
  // 外键必须在事务外打开：SQLite 规定 PRAGMA foreign_keys 在事务内是空操作。
  // 部分驱动不支持这条 PRAGMA，此时继续建表——引用完整性由应用层保证，
  // 但能开就开：孤儿行（题删了、修订还在）是最难查的一类脏数据。
  try {
    await db.exec("PRAGMA foreign_keys = ON");
  } catch {
    // 驱动不支持，忽略。
  }

  await db.transaction(async () => {
    for (const statement of SCHEMA_STATEMENTS) {
      await db.exec(statement);
    }

    const current = await readSchemaVersion(db);
    for (const migration of MIGRATIONS) {
      if (current < migration.version) {
        await migration.apply(db);
      }
    }

    if (current !== SCHEMA_VERSION) {
      await db.exec(`PRAGMA user_version = ${SCHEMA_VERSION}`);
    }
  });
  return SCHEMA_VERSION;
}
