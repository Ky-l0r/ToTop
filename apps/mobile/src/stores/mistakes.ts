import {
  countMistakes,
  listMistakeKpoints,
  listMistakes,
  type KpointOption,
  type MistakeListItem,
} from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 易错题页的 store。
 *
 * 只有四个筛选：科目 / 知识点 / 错因 / 时间。
 * 没有搜索框（第五个条件会和前四个互相打架），也没有状态分段——
 * "掌握没掌握"去知识点页看，这一页只负责翻旧账。
 */
export type TimeRange = 0 | 7 | 30;

export const useMistakesStore = defineStore("mistakes", () => {
  const items = ref<MistakeListItem[]>([]);
  const kpointOptions = ref<KpointOption[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const error = ref("");

  const subject = ref("");
  const kpointId = ref("");
  const errorType = ref("");
  const days = ref<TimeRange>(0);

  const hasFilter = computed(
    () =>
      subject.value !== "" || kpointId.value !== "" || errorType.value !== "" || days.value !== 0,
  );

  const sinceUtc = computed(() => (days.value === 0 ? null : Date.now() - days.value * 86_400_000));

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      const filter = {
        subject: subject.value || null,
        kpointId: kpointId.value || null,
        errorType: errorType.value || null,
        sinceUtc: sinceUtc.value,
      };
      items.value = await listMistakes(db, filter, 200);
      total.value = await countMistakes(db, filter);
      if (kpointOptions.value.length === 0) {
        kpointOptions.value = await listMistakeKpoints(db);
      }
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到错题";
    } finally {
      loading.value = false;
    }
  }

  function setSubject(next: string): void {
    subject.value = next;
    void load();
  }

  function setKpoint(next: string): void {
    kpointId.value = next;
    void load();
  }

  function setErrorType(next: string): void {
    errorType.value = next;
    void load();
  }

  function setDays(next: TimeRange): void {
    days.value = next;
    void load();
  }

  function clearFilters(): void {
    subject.value = "";
    kpointId.value = "";
    errorType.value = "";
    days.value = 0;
    void load();
  }

  return {
    items,
    kpointOptions,
    total,
    loading,
    error,
    subject,
    kpointId,
    errorType,
    days,
    hasFilter,
    load,
    setSubject,
    setKpoint,
    setErrorType,
    setDays,
    clearFilters,
  };
});
