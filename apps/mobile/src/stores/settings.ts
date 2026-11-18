import { SCHEMA_VERSION, SETTINGS_KEYS, type SettingKey } from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { ref } from "vue";

/**
 * 设置页的状态：性能档位、科目、导入记录、版本信息。
 *
 * 模型端点与 Key 不在这里——它们由 llm store 持有（拍题页也要用同一份）。
 *
 * 删掉的东西不在这里出现：卡点笔记、复习打卡相关的开关在 1.0 全部下线，
 * "设置项与页面能力一一对应"是这一页唯一的标准。
 */
export type PerformanceTier = "auto" | "high" | "saver";

export const useSettingsStore = defineStore("settings", () => {
  const tier = ref<PerformanceTier>("auto");
  const subjects = ref<string[]>([]);
  const importedAt = ref(0);
  const error = ref("");
  const loading = ref(false);

  const schemaVersion = SCHEMA_VERSION;

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { settings } = await getDatabase();
      const rawTier = await settings.get(SETTINGS_KEYS.performanceTier);
      tier.value = rawTier === "high" || rawTier === "saver" ? rawTier : "auto";

      const rawSubjects = await settings.get(SETTINGS_KEYS.subjects);
      subjects.value = parseSubjects(rawSubjects);

      const rawImported = await settings.get(SETTINGS_KEYS.legacyImportedAt);
      importedAt.value = rawImported ? Number(rawImported) : 0;
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到设置";
    } finally {
      loading.value = false;
    }
  }

  async function setTier(next: PerformanceTier): Promise<void> {
    tier.value = next;
    await write(SETTINGS_KEYS.performanceTier, next);
  }

  /** 科目多选：点一次切换一次，立刻落库。 */
  async function toggleSubject(subject: string): Promise<void> {
    const index = subjects.value.indexOf(subject);
    if (index >= 0) subjects.value.splice(index, 1);
    else subjects.value.push(subject);
    await write(SETTINGS_KEYS.subjects, JSON.stringify(subjects.value));
    await write(SETTINGS_KEYS.subjectsPicked, "true");
  }

  async function write(key: SettingKey, value: string): Promise<void> {
    try {
      const { settings } = await getDatabase();
      await settings.put(key, value);
      error.value = "";
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "保存失败";
    }
  }

  return { tier, subjects, importedAt, error, loading, schemaVersion, load, setTier, toggleSubject };
});

function parseSubjects(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}
