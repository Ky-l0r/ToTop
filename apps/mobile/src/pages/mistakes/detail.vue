<script setup lang="ts">
import { getDatabase } from "@/services/db";
import { getMistake, labelOf, type MistakeDetail, type Subject } from "@totop/core";
import { onLoad } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

/**
 * 错题详情。
 *
 * 只有题目、解答、知识点三块。状态区、验算、存资料库这些已下线的区块不在这里出现，
 * 展示掌握程度的地方只有知识点页——两处数值不一致比不给看更糟。
 */
const detail = ref<MistakeDetail | null>(null);
const error = ref("");
const showStudentSteps = ref(false);

onLoad((query) => {
  const id = query?.id ?? "";
  if (id) void load(decodeURIComponent(id));
});

async function load(id: string): Promise<void> {
  error.value = "";
  try {
    const { db } = await getDatabase();
    detail.value = await getMistake(db, id);
    if (!detail.value) error.value = "这条错题找不到了";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "读不到这条错题";
  }
}

const subjectName = computed(() =>
  detail.value ? labelOf(detail.value.subject as Subject) : "",
);

function formatDate(utc: number): string {
  const date = new Date(utc);
  const pad = (value: number): string => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
</script>

<template>
  <view class="page">
    <text v-if="error" class="error-text">{{ error }}</text>

    <template v-if="detail">
      <view class="card">
        <view class="meta-row">
          <text class="meta">{{ subjectName }} · {{ detail.questionType }}</text>
          <text class="meta">{{ formatDate(detail.createdAt) }}</text>
        </view>
        <text class="stem">{{ detail.stem }}</text>
        <text v-if="detail.ask" class="ask">问：{{ detail.ask }}</text>
      </view>

      <view class="card">
        <text class="block-title">答案</text>
        <text class="answer">{{ detail.finalAnswer }}</text>
      </view>

      <view v-if="detail.errorType" class="card error">
        <text class="block-title">当时错在哪</text>
        <text class="error-head">{{ detail.errorType }}</text>
        <text class="error-body">{{ detail.errorExplain }}</text>
      </view>

      <view v-if="detail.steps.length" class="card">
        <text class="block-title">标准步骤</text>
        <view v-for="step in detail.steps" :key="step.idx" class="step">
          <text class="step-no">{{ step.idx }}</text>
          <view class="step-body">
            <text class="step-content">{{ step.content }}</text>
            <text v-if="step.why" class="step-why">{{ step.why }}</text>
          </view>
        </view>
      </view>

      <view v-if="detail.studentSteps.length" class="card">
        <view class="fold-row" @tap="showStudentSteps = !showStudentSteps">
          <text class="block-title">我当时的写法</text>
          <text class="fold">{{ showStudentSteps ? "收起" : "展开" }}</text>
        </view>
        <view v-if="showStudentSteps" class="student-steps">
          <text v-for="(step, index) in detail.studentSteps" :key="index" class="student-step">
            {{ step.content }}
          </text>
        </view>
      </view>

      <view v-if="detail.revision" class="card">
        <text class="block-title">改写前的答案</text>
        <text class="answer-muted">{{ detail.revision.finalAnswer }}</text>
        <text class="meta">改动时间 {{ formatDate(detail.revision.createdAt) }}</text>
      </view>

      <view v-if="detail.kpointName" class="card">
        <text class="block-title">这题考的是</text>
        <text class="kpoint">{{ detail.kpointName }}</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 28px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}

.meta-row {
  display: flex;
  justify-content: space-between;
}
.meta {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.stem {
  font-size: 15px;
  color: var(--tt-on-surface);
  line-height: 1.6;
}
.ask {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
}

.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--tt-on-surface-variant);
}
.answer {
  font-size: 18px;
  font-weight: 600;
  color: var(--tt-primary);
}
.answer-muted {
  font-size: 15px;
  color: var(--tt-on-surface-variant);
}

.error {
  background: var(--tt-error-container);
}
.error .block-title,
.error-head {
  color: var(--tt-error);
}
.error-head {
  font-size: 13px;
  font-weight: 600;
}
.error-body {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.55;
}

.step {
  display: flex;
  gap: 8px;
}
.step-no {
  width: 18px;
  font-size: 12px;
  color: var(--tt-primary);
  text-align: center;
}
.step-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.step-content {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.55;
}
.step-why {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.fold-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fold {
  font-size: 12px;
  color: var(--tt-primary);
}
.student-steps {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.student-step {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.kpoint {
  align-self: flex-start;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--tt-primary);
  background: var(--tt-primary-container);
}

.error-text {
  font-size: 12px;
  color: var(--tt-error);
}
</style>
