import type { SqlExecutor } from "../db/executor";

/**
 * 设置键。
 *
 * 前十二个**沿用 0.8.0 的键名**，导入旧备份时学生的偏好（讲解模式、科目、学期开始日）
 * 直接生效，不需要再做一次映射；只有末尾两个是 1.0 新增。
 */
export const SETTINGS_KEYS = {
  teachingMode: "mode",
  ttsEnabled: "ttsEnabled",
  ttsSpeed: "ttsSpeed",
  promptVersion: "promptVersion",
  subjects: "subjects",
  subjectsPicked: "subjectsPicked",
  firstLaunchDone: "firstLaunchDone",
  examClassSize: "examClassSize",
  examGradeSize: "examGradeSize",
  examTermStart: "examTermStart",
  examTargetGlobal: "examTargetGlobal",

  /** 新增：上次用的输入方式（voice / text），拍题页与陪伴页共用。 */
  inputMode: "inputMode",
  /** 新增：性能档位（auto / high / saver）。 */
  performanceTier: "performanceTier",
  /** 新增：上次从 0.8.0 导入的时间，用来在设置页显示"已迁移"。 */
  legacyImportedAt: "import.legacy.at",
  /** 新增：上次冷启动到首页有数据用了多少毫秒（真机验收用）。 */
  firstContentMs: "perf.firstContentMs",

  /** 新增：模型端点与模型名。**API Key 不在这里**——它只能进系统钥匙串。 */
  llmBaseUrl: "llmBaseUrl",
  llmModel: "llmModel",
} as const;

export type SettingKey = (typeof SETTINGS_KEYS)[keyof typeof SETTINGS_KEYS];

/**
 * 设置读写。
 *
 * 只做搬运与类型转换，不缓存：设置项很少、读得也不频繁，
 * 加一层内存缓存就要处理"另一个页面改了没通知我"的问题，不值得。
 */
export class SettingsRepo {
  constructor(
    private readonly db: SqlExecutor,
    private readonly now: () => number = () => Date.now(),
  ) {}

  async get(key: SettingKey): Promise<string | null> {
    const rows = await this.db.all<{ value: string }>("SELECT value FROM settings WHERE key = ?", [
      key,
    ]);
    return rows[0]?.value ?? null;
  }

  async put(key: SettingKey, value: string): Promise<void> {
    await this.db.run("INSERT OR REPLACE INTO settings (key, value, updatedAt) VALUES (?, ?, ?)", [
      key,
      value,
      this.now(),
    ]);
  }

  async getAll(): Promise<Record<string, string>> {
    const rows = await this.db.all<{ key: string; value: string }>(
      "SELECT key, value FROM settings",
    );
    const result: Record<string, string> = {};
    for (const row of rows) result[row.key] = row.value;
    return result;
  }

  async remove(key: SettingKey): Promise<void> {
    await this.db.run("DELETE FROM settings WHERE key = ?", [key]);
  }

  async getBoolean(key: SettingKey, fallback: boolean): Promise<boolean> {
    const raw = await this.get(key);
    if (raw === null) return fallback;
    return raw === "true" || raw === "1";
  }

  async putBoolean(key: SettingKey, value: boolean): Promise<void> {
    await this.put(key, value ? "true" : "false");
  }

  async getNumber(key: SettingKey, fallback: number): Promise<number> {
    const raw = await this.get(key);
    if (raw === null) return fallback;
    const parsed = Number(raw);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  async putNumber(key: SettingKey, value: number): Promise<void> {
    await this.put(key, String(value));
  }
}
