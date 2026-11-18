import {
  chapterHeat,
  listKpoints,
  type ChapterHeat,
  type KpointListItem,
  type MasteryBand,
} from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 知识点页的状态。
 *
 * 只有一套筛选：科目 + 掌握度区间。0.8.0 上下各有一套（上面按科目、下面按章节），
 * 两套同时生效时学生根本说不清"我现在看到的是哪一档"。
 */
export const useKnowledgeStore = defineStore("knowledge", () => {
  const subject = ref("");
  const band = ref<MasteryBand>("all");
  const items = ref<KpointListItem[]>([]);
  const heat = ref<ChapterHeat[]>([]);
  const allSubjects = ref<string[]>([]);
  const loading = ref(true);
  const error = ref("");

  /** 有知识点的科目才出现在筛选里，避免满屏空选项。 */
  const subjectOptions = computed(() => allSubjects.value);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      items.value = await listKpoints(db, {
        subject: subject.value || null,
        band: band.value,
      });
      heat.value = await chapterHeat(db, subject.value || null);

      if (allSubjects.value.length === 0) {
        const everything = await listKpoints(db, {});
        allSubjects.value = Array.from(new Set(everything.map((item) => item.subject)));
      }
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到知识点";
    } finally {
      loading.value = false;
    }
  }

  function setSubject(next: string): void {
    subject.value = next;
    void load();
  }

  function setBand(next: MasteryBand): void {
    band.value = next;
    void load();
  }

  return { subject, band, items, heat, subjectOptions, loading, error, load, setSubject, setBand };
});
