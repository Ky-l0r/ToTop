import { SETTINGS_KEYS } from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 模型端点与 Key。
 *
 * 单独成一个 store，是因为它现在有两个使用方：拍题页（发请求）与设置页（填/改）。
 * 各存一份的话，改完设置回到拍题页还是旧值——这种问题查起来最费时间。
 *
 * **Key 只在内存**：系统钥匙串要等原生插件（见 security 说明），在那之前
 * 写 settings 表或 uni storage 都是明文。代价是杀进程要重填，界面必须说清楚。
 */
export const useLlmStore = defineStore("llm", () => {
  const baseUrl = ref("");
  const model = ref("");
  const apiKey = ref("");
  const configError = ref("");
  const loaded = ref(false);

  const configured = computed(
    () => baseUrl.value.trim() !== "" && model.value.trim() !== "" && apiKey.value.trim() !== "",
  );

  /** 读已保存的地址与模型名。Key 读不回来，只能每次手填。 */
  async function load(): Promise<void> {
    if (loaded.value) return;
    loaded.value = true;
    configError.value = "";
    try {
      const { settings } = await getDatabase();
      baseUrl.value = (await settings.get(SETTINGS_KEYS.llmBaseUrl)) ?? "";
      model.value = (await settings.get(SETTINGS_KEYS.llmModel)) ?? "";
    } catch (error) {
      const detail = error instanceof Error ? error.message : "本地库不可用";
      configError.value = `读不到已保存的设置：${detail}`;
    }
  }

  /** 保存地址与模型名（不含 Key）。 */
  async function save(): Promise<boolean> {
    configError.value = "";
    try {
      const { settings } = await getDatabase();
      await settings.put(SETTINGS_KEYS.llmBaseUrl, baseUrl.value.trim());
      await settings.put(SETTINGS_KEYS.llmModel, model.value.trim());
      return true;
    } catch (error) {
      const detail = error instanceof Error ? error.message : "本地库不可用";
      configError.value = `这次能直接用，但没保存下来：${detail}`;
      return false;
    }
  }

  function endpoint(): { baseUrl: string; apiKey: string } {
    return { baseUrl: baseUrl.value, apiKey: apiKey.value };
  }

  return { baseUrl, model, apiKey, configError, configured, loaded, load, save, endpoint };
});
