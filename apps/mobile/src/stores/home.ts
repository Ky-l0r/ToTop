import { launchedAt } from "@/boot";
import { homeStats, SETTINGS_KEYS, weakKpoints, type HomeStats, type WeakKpoint } from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { ref } from "vue";

/**
 * 首页数据。
 *
 * 三个数字与不熟知识点都由 core 的同一个函数出数：首页、易错题页、学情页
 * 显示的值必须一致，各算一遍是 0.8.0 的老毛病。
 */
export const useHomeStore = defineStore("home", () => {
  const stats = ref<HomeStats | null>(null);
  const weak = ref<WeakKpoint[]>([]);
  const loading = ref(true);
  const error = ref("");
  /** 首屏耗时每次冷启动只记一次：页面来回切不该覆盖它。 */
  let recorded = false;

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      stats.value = await homeStats(db, Date.now());
      weak.value = await weakKpoints(db, 5);

      // 首屏耗时记在设置里：真机上没有性能面板，这是唯一能读到的数。
      // 只记第一次（冷启动那一次），页面来回切不该覆盖它。
      if (!recorded) {
        recorded = true;
        const { settings } = await getDatabase();
        await settings.put(SETTINGS_KEYS.firstContentMs, String(Date.now() - launchedAt));
      }
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到本地数据";
    } finally {
      loading.value = false;
    }
  }

  return { stats, weak, loading, error, load };
});
