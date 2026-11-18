import { SCHEMA_STATEMENTS, SCHEMA_VERSION } from "./schema";
import type { SqlExecutor } from "./executor";

/** 当前库版本（PRAGMA user_version）。空库返回 0。 */
export async function readSchemaVersion(db: SqlExecutor): Promise<number> {
  const rows = await db.all<{ user_version: number }>("PRAGMA user_version");
  return rows[0]?.user_version ?? 0;
}

/**
 * 建表并记录版本。可重复调用：语句全部是 IF NOT EXISTS。
 *
 * 为什么把 user_version 与建表放在一起：1.0 是新库，没有"从 v1 升到 v2"的历史，
 * 版本号只用来**判断这份库是不是本版本建的**，避免旧实验库里缺列时静默跑下去。
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
    if (current !== SCHEMA_VERSION) {
      await db.exec(`PRAGMA user_version = ${SCHEMA_VERSION}`);
    }
  });
  return SCHEMA_VERSION;
}
