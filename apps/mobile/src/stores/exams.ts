import {
  examTrend,
  latestExam,
  listExams,
  saveExam,
  scoredSubjects,
  type ExamSessionRow,
} from "@totop/core";
import { getDatabase } from "@/services/db";
import { defineStore } from "pinia";
import { computed, ref } from "vue";

/**
 * 学情的状态。
 *
 * `subject` 为 null 表示"全部科目"：此时只统计各科都录过的场次，
 * 避免一场只录了数学的周测把总分趋势拉出假低谷。
 */
export const useExamsStore = defineStore("exams", () => {
  const subject = ref<string | null>(null);
  const subjects = ref<string[]>([]);
  const latest = ref<ExamSessionRow | null>(null);
  const sessions = ref<ExamSessionRow[]>([]);
  const trend = ref<Array<{ occurredOn: number; rate: number }>>([]);
  const loading = ref(true);
  const error = ref("");

  const hasData = computed(() => sessions.value.length > 0);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = "";
    try {
      const { db } = await getDatabase();
      subjects.value = await scoredSubjects(db);
      latest.value = await latestExam(db, subject.value);
      sessions.value = await listExams(db, subject.value, 12);
      trend.value = await examTrend(db, subject.value, 12);
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "读不到成绩";
    } finally {
      loading.value = false;
    }
  }

  /** 切科目：整块换掉，不需要手动刷新。 */
  function setSubject(next: string | null): void {
    subject.value = next;
    void load();
  }

  async function addExam(input: {
    name: string;
    occurredOn: number;
    scores: Array<{ subject: string; score: number; fullScore: number }>;
  }): Promise<boolean> {
    if (input.scores.length === 0) {
      error.value = "至少填一科成绩";
      return false;
    }
    try {
      const { db } = await getDatabase();
      await saveExam(db, {
        name: input.name,
        occurredOn: input.occurredOn,
        kind: "monthly",
        scores: input.scores,
        nowUtc: Date.now(),
      });
      await load();
      return true;
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : "保存失败";
      return false;
    }
  }

  return { subject, subjects, latest, sessions, trend, loading, error, hasData, load, setSubject, addExam };
});
