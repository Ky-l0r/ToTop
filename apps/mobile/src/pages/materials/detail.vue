<script setup lang="ts">
import { getDatabase } from "@/services/db";
import { explainMaterial } from "@/services/material-import";
import { deleteMaterial, getMaterial, labelOf, saveExplanation, type MaterialDetail, type Subject } from "@totop/core";
import { onLoad } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

/**
 * 资料详情。
 *
 * 原文（题干、答案、步骤、易错点）与"AI 讲一遍"严格分开：
 * 后者是副本，不覆盖原文。学生要能分清哪句是老师给的、哪句是 AI 说的。
 */
const detail = ref<MaterialDetail | null>(null);
const error = ref("");
const explaining = ref(false);
const explainError = ref("");

onLoad((query) => {
  const id = query?.id;
  if (typeof id === "string" && id !== "") void load(decodeURIComponent(id));
});

async function load(id: string): Promise<void> {
  error.value = "";
  try {
    const { db } = await getDatabase();
    detail.value = await getMaterial(db, id);
    if (!detail.value) error.value = "这份资料找不到了";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "读不到这份资料";
  }
}

/** 让 AI 讲一遍；成功后存成这条资料的讲法，下次直接看。 */
async function askAi(): Promise<void> {
  if (!detail.value || explaining.value) return;
  explaining.value = true;
  explainError.value = "";
  try {
    const text = await explainMaterial({
      subject: detail.value.subject,
      grade: "",
      stem: detail.value.stem,
      answer: detail.value.answer,
      steps: detail.value.steps,
      pitfalls: detail.value.pitfalls,
    });
    const { db } = await getDatabase();
    await saveExplanation(db, detail.value.id, text);
    detail.value = { ...detail.value, explanation: text, hasExplanation: true };
  } catch (caught) {
    explainError.value = caught instanceof Error ? caught.message : "解析失败，可以重试";
  } finally {
    explaining.value = false;
  }
}

function removeMaterial(): void {
  if (!detail.value) return;
  const id = detail.value.id;
  uni.showModal({
    title: "删除这份资料",
    content: "删掉之后不能恢复",
    success: (result) => {
      if (result.confirm) void doRemove(id);
    },
  });
}

async function doRemove(id: string): Promise<void> {
  try {
    const { db } = await getDatabase();
    await deleteMaterial(db, id);
    uni.navigateBack();
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "删除失败";
  }
}

const subjectName = computed(() =>
  detail.value ? labelOf(detail.value.subject as Subject) : "",
);

function formatDate(utc: number): string {
  const date = new Date(utc);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
</script>

<template>
  <view class="page">
    <text v-if="error" class="error">{{ error }}</text>

    <template v-if="detail">
      <view class="card">
        <view class="meta-row">
          <text class="meta">{{ subjectName }} · {{ detail.origin }}</text>
          <text class="meta">{{ formatDate(detail.createdAtUtc) }}</text>
        </view>
        <text class="stem">{{ detail.stem }}</text>
      </view>

      <view class="card">
        <text class="block-title">答案</text>
        <text class="answer">{{ detail.answer || "这份资料里没有答案" }}</text>
      </view>

      <view v-if="detail.steps.length" class="card">
        <text class="block-title">原文解析</text>
        <view v-for="step in detail.steps" :key="step.idx" class="step">
          <text class="step-no">{{ step.idx }}</text>
          <view class="step-body">
            <text class="step-content">{{ step.content }}</text>
            <text v-if="step.why" class="step-why">{{ step.why }}</text>
          </view>
        </view>
      </view>

      <view v-if="detail.pitfalls.length" class="card warn">
        <text class="block-title">易错点</text>
        <text v-for="(item, index) in detail.pitfalls" :key="index" class="warn-text">
          · {{ item }}
        </text>
      </view>

      <!-- AI 讲法：与原文分开，标清来源 -->
      <view v-if="detail.explanation" class="card ai">
        <text class="block-title">AI 讲一遍</text>
        <text class="explain">{{ detail.explanation }}</text>
      </view>

      <view v-else class="card ai">
        <text class="block-title">AI 讲一遍</text>
        <text class="hint">把这份标准答案讲成能跟着想一遍的过程。大概十几秒。</text>
        <view class="primary" :class="{ 'primary-off': explaining }" @tap="askAi">
          <text class="primary-text">{{ explaining ? "正在读…" : "让 AI 讲一遍" }}</text>
        </view>
        <text v-if="explainError" class="error">{{ explainError }}</text>
      </view>

      <view class="ghost" @tap="removeMaterial">
        <text class="ghost-text">删除这份资料</text>
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
.card-title,
.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--tt-on-surface-variant);
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
.answer {
  font-size: 16px;
  font-weight: 600;
  color: var(--tt-primary);
  line-height: 1.5;
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

.warn {
  background: var(--tt-warning-container);
}
.warn .block-title,
.warn-text {
  color: var(--tt-warning);
}
.warn-text {
  font-size: 13px;
  line-height: 1.5;
}

.ai {
  background: var(--tt-primary-container);
}
.explain {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.7;
}

.hint {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.primary {
  align-self: flex-start;
  padding: 8px 16px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-primary);
}
.primary-off {
  opacity: 0.5;
}
.primary-text {
  font-size: 13px;
  color: #ffffff;
}

.ghost {
  align-self: flex-start;
  padding: 8px 16px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.ghost-text {
  font-size: 13px;
  color: var(--tt-on-surface);
}

.error {
  font-size: 12px;
  color: var(--tt-error);
}
</style>
