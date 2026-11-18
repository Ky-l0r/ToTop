import { ensureSchema, SettingsRepo, type SqlExecutor } from "@totop/core";
import { openAppDatabase } from "@/platform/db/plus-sqlite";

/**
 * 应用数据库的单例入口。
 *
 * 为什么做成"首次调用才打开"：打开库要读文件、跑建表语句，
 * 放在应用启动路径上会拖慢首屏，而这套数据在首页用不到。
 *
 * 失败后不缓存结果：用户可能只是第一次进 App 时存储还没就绪，
 * 把一次失败固化成永久不可用，表现就是"设置项永远是空的"。
 */
interface DatabaseHandle {
  db: SqlExecutor;
  settings: SettingsRepo;
}

let pending: Promise<DatabaseHandle> | null = null;

export function getDatabase(): Promise<DatabaseHandle> {
  if (!pending) {
    pending = (async () => {
      const db = await openAppDatabase();
      await ensureSchema(db);
      return { db, settings: new SettingsRepo(db) };
    })().catch((error: unknown) => {
      pending = null;
      throw error;
    });
  }
  return pending;
}
