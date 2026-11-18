import { homeStats, weakKpoints, type HomeStats, type WeakKpoint } from "@totop/core";
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

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      stats.value = await homeStats(db, Date.now());
      weak.value = await weakKpoints(db, 5);
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到本地数据";
    } finally {
      loading.value = false;
    }
  }

  return { stats, weak, loading, error, load };
});
