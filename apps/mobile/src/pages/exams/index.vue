<script setup lang="ts">
import { useExamsStore } from "@/stores/exams";
import { labelOf, SUBJECTS, type Subject } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";
import { computed, reactive, ref } from "vue";

/**
 * 学情。
 *
 * 科目选择是整页第一个控件；选定单科后，成绩区只出现该科，不串科。
 * 没有成绩时给可操作的空态（"录入一次月考"），不是空白。
 *
 * 趋势用横条画得分率，不引图表库：这里只有 12 个点，横条足够看出起伏，
 * 而 canvas 图表在 App 端与 H5 端的渲染差异又是一堆要处理的边界。
 */
const store = useExamsStore();

const showForm = ref(false);
const formError = ref("");
const form = reactive({
  name: "",
  date: todayString(),
  picked: {} as Record<string, { score: string; full: string }>,
});

const latest = computed(() => store.latest);
const latestRate = computed(() => {
  const session = latest.value;
  if (!session || session.scores.length === 0) return 0;
  const row = session.scores[0];
  if (!row || row.fullScore <= 0) return 0;
  return row.score / row.fullScore;
});

onShow(() => {
  void store.load();
});

function todayString(): string {
  const now = new Date();
  const two = (value: number): string => String(value).padStart(2, "0");
  return `${now.getFullYear()}-${two(now.getMonth() + 1)}-${two(now.getDate())}`;
}

function setSubject(next: string | null): void {
  store.setSubject(next);
}

function togglePick(subject: string): void {
  if (form.picked[subject]) {
    delete form.picked[subject];
    return;
  }
  form.picked[subject] = { score: "", full: "100" };
}

function isPicked(subject: string): boolean {
  return Boolean(form.picked[subject]);
}

function onDateChange(event: { detail: { value: string } }): void {
  form.date = event.detail.value;
}

async function submit(): Promise<void> {
  formError.value = "";
  const scores = Object.entries(form.picked)
    .map(([subject, entry]) => ({
      subject,
      score: Number(entry.score),
      fullScore: Number(entry.full) || 100,
    }))
    .filter((item) => Number.isFinite(item.score) && item.score > 0 && item.fullScore > 0);

  if (scores.length === 0) {
    formError.value = "至少填一科分数";
    return;
  }

  const occurredOn = new Date(`${form.date}T00:00:00`).getTime();
  const ok = await store.addExam({
    name: form.name.trim(),
    occurredOn: Number.isFinite(occurredOn) ? occurredOn : Date.now(),
    scores,
  });
  if (ok) {
    showForm.value = false;
    form.picked = {};
    form.name = "";
  } else {
    formError.value = store.error;
  }
}

function percent(rate: number): string {
  return `${Math.round(rate * 100)}%`;
}

function formatDate(utc: number): string {
  const date = new Date(utc);
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`;
}

function subjectName(code: string): string {
  return labelOf(code as Subject);
}
</script>

<template>
  <view class="page">
    <!-- 科目选择：整页第一个控件 -->
    <scroll-view class="subjects" scroll-x :show-scrollbar="false">
      <view class="subject-row">
        <view
          class="chip"
          :class="{ 'chip-on': store.subject === null }"
          @tap="setSubject(null)"
        >
          <text class="chip-text">全部科目</text>
        </view>
        <view
          v-for="subject in store.subjects"
          :key="subject"
          class="chip"
          :class="{ 'chip-on': store.subject === subject }"
          @tap="setSubject(subject)"
        >
          <text class="chip-text">{{ subjectName(subject) }}</text>
        </view>
      </view>
    </scroll-view>

    <text v-if="store.error" class="error">{{ store.error }}</text>

    <!-- 空态：可操作，不是空白 -->
    <view v-if="!store.loading && !store.hasData" class="empty">
      <text class="empty-title">还没有成绩</text>
      <text class="empty-hint">录入一次月考，就能看到趋势</text>
      <view class="primary" @tap="showForm = true">
        <text class="primary-text">录入一次考试</text>
      </view>
    </view>

    <template v-if="latest">
      <view class="card">
        <view class="card-head">
          <text class="card-title">{{ latest.name || "最近一次考试" }}</text>
          <text class="card-date">{{ formatDate(latest.occurredOn) }}</text>
        </view>
        <view class="score-line">
          <text class="score">{{ latest.scores[0]?.score ?? 0 }}</text>
          <text class="score-full">/ {{ latest.scores[0]?.fullScore ?? 0 }}</text>
          <text class="rate">{{ percent(latestRate) }}</text>
        </view>
        <text class="hint">
          {{ store.subject ? subjectName(store.subject) : "全部科目平均" }}
        </text>
      </view>
    </template>

    <view v-if="store.trend.length" class="card">
      <text class="card-title">得分率趋势</text>
      <view class="trend">
        <view v-for="point in store.trend" :key="point.occurredOn" class="bar-row">
          <text class="bar-date">{{ formatDate(point.occurredOn) }}</text>
          <view class="bar-track">
            <view class="bar-fill" :style="{ width: percent(point.rate) }" />
          </view>
          <text class="bar-value">{{ percent(point.rate) }}</text>
        </view>
      </view>
      <text class="hint">只画当前选中科目；选"全部科目"时是各科平均。</text>
    </view>

    <view v-if="store.hasData" class="ghost" @tap="showForm = !showForm">
      <text class="ghost-text">{{ showForm ? "收起录入" : "再录一次" }}</text>
    </view>

    <!-- 录入表单 -->
    <view v-if="showForm" class="card">
      <text class="card-title">录入考试</text>
      <input v-model="form.name" class="field" placeholder="名称，如 十月月考" />

      <picker mode="date" :value="form.date" @change="onDateChange">
        <view class="field field-picker">
          <text class="picker-text">{{ form.date }}</text>
        </view>
      </picker>

      <view class="pick-row">
        <view
          v-for="subject in SUBJECTS"
          :key="subject"
          class="pick"
          :class="{ 'pick-on': isPicked(subject) }"
          @tap="togglePick(subject)"
        >
          <text class="pick-text">{{ labelOf(subject) }}</text>
        </view>
      </view>

      <view v-for="(entry, code) in form.picked" :key="code" class="score-row">
        <text class="score-subject">{{ subjectName(code) }}</text>
        <input v-model="entry.score" class="field field-small" type="digit" placeholder="分数" />
        <input v-model="entry.full" class="field field-small" type="digit" placeholder="满分" />
      </view>

      <text v-if="formError" class="error">{{ formError }}</text>
      <view class="primary" @tap="submit">
        <text class="primary-text">保存</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 0 28px;
}

.subjects {
  width: 100%;
  white-space: nowrap;
}
.subject-row {
  display: flex;
  gap: 8px;
  padding: 4px 14px;
}
.chip {
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--tt-surface-variant);
  border: 1px solid transparent;
}
.chip-on {
  background: var(--tt-primary-container);
  border-color: var(--tt-primary);
}
.chip-text {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
}
.chip-on .chip-text {
  color: var(--tt-primary);
  font-weight: 600;
}

.card {
  margin: 0 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--tt-on-surface);
}
.card-date {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.score-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.score {
  font-size: 26px;
  font-weight: 600;
  color: var(--tt-primary);
}
.score-full {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
}
.rate {
  margin-left: auto;
  font-size: 14px;
  color: var(--tt-on-surface);
}

.hint {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.trend {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar-date {
  width: 64px;
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.bar-track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--tt-surface);
  overflow: hidden;
}
.bar-fill {
  height: 8px;
  border-radius: 4px;
  background: var(--tt-primary);
}
.bar-value {
  width: 38px;
  text-align: right;
  font-size: 11px;
  color: var(--tt-on-surface);
}

.empty {
  margin: 0 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 40px 16px;
}
.empty-title {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.empty-hint {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}

.primary {
  margin: 0 14px;
  align-self: center;
  padding: 9px 22px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-primary);
}
.card .primary {
  margin: 0;
}
.primary-text {
  font-size: 14px;
  color: #ffffff;
}

.ghost {
  margin: 0 14px;
  align-self: flex-start;
  padding: 7px 14px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.ghost-text {
  font-size: 13px;
  color: var(--tt-on-surface);
}

.field {
  width: 100%;
  height: 40px;
  padding: 0 10px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
  border: 1px solid var(--tt-outline);
  font-size: 13px;
  color: var(--tt-on-surface);
}
.field-picker {
  display: flex;
  align-items: center;
}
.picker-text {
  font-size: 13px;
  color: var(--tt-on-surface);
}
.field-small {
  width: 72px;
}

.pick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.pick {
  padding: 5px 10px;
  border-radius: 999px;
  background: var(--tt-surface);
  border: 1px solid transparent;
}
.pick-on {
  background: var(--tt-primary-container);
  border-color: var(--tt-primary);
}
.pick-text {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}
.pick-on .pick-text {
  color: var(--tt-primary);
  font-weight: 600;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.score-subject {
  width: 52px;
  font-size: 13px;
  color: var(--tt-on-surface);
}

.error {
  font-size: 12px;
  color: var(--tt-error);
}
</style>
