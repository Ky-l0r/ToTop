<script setup lang="ts">
import { getDatabase } from "@/services/db";
import { heatOf, kpointDetail, labelOf, type MasterySnapshot, type Subject } from "@totop/core";
import { onLoad } from "@dcloudio/uni-app";
import { computed, ref } from "vue";

/**
 * 知识点详情：看掌握程度的唯一入口。
 *
 * 只有数值、最近变化、关联错题。没有趋势图——趋势要用"连续多次练习"才画得出来，
 * 而现在数据还不够；画一条两个点的线只会让人误以为掌握度在稳定上升。
 */
const detail = ref<MasterySnapshot | null>(null);
const error = ref("");

onLoad((query) => {
  const id = query?.id;
  if (typeof id === "string" && id !== "") void load(decodeURIComponent(id));
});

async function load(id: string): Promise<void> {
  error.value = "";
  try {
    const { db } = await getDatabase();
    detail.value = await kpointDetail(db, id);
    if (!detail.value) error.value = "这个知识点找不到了";
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : "读不到这个知识点";
  }
}

const scorePercent = computed(() =>
  detail.value ? `${Math.round(detail.value.score * 100)}%` : "0%",
);

const heatClass = computed(() => (detail.value ? `heat-${heatOf(detail.value.score)}` : ""));

const levelText = computed(() => {
  if (!detail.value) return "";
  const heat = heatOf(detail.value.score);
  if (heat === "red") return "不熟，建议先练这一块";
  if (heat === "green") return "已经掌握";
  return "半熟，多练几道就上去了";
});

/** 最近 5 次变化，新的在上。 */
const recentEvents = computed(() => (detail.value?.history ?? []).slice(-5).reverse());

const eventLabel: Record<string, string> = {
  correct: "做对一题",
  wrong: "仍不懂",
  mistake: "新增错题",
};

function formatDate(utc: number): string {
  const date = new Date(utc);
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`;
}

function openMistake(mistakeId: string): void {
  uni.navigateTo({ url: `/pages/mistakes/detail?id=${encodeURIComponent(mistakeId)}` });
}

function notReady(): void {
  uni.showToast({ title: "巩固练习在下一批", icon: "none" });
}
</script>

<template>
  <view class="page">
    <text v-if="error" class="error">{{ error }}</text>

    <template v-if="detail">
      <view class="card">
        <view class="head">
          <text class="name">{{ detail.name }}</text>
          <text class="score" :class="heatClass">{{ scorePercent }}</text>
        </view>
        <text class="meta">{{ labelOf(detail.subject as Subject) }} · {{ detail.chapter }}</text>
        <text class="level">{{ levelText }}</text>
      </view>

      <view class="card">
        <text class="card-title">最近变化</text>
        <text v-if="recentEvents.length === 0" class="hint">
          还没有练习记录。做错题会记一次，练对了也会记一次。
        </text>
        <view v-for="(event, index) in recentEvents" :key="index" class="event">
          <text class="event-kind">{{ eventLabel[event.kind] ?? event.kind }}</text>
          <text class="event-time">{{ formatDate(event.at) }}</text>
          <text class="event-score">{{ Math.round(event.score * 100) }}%</text>
        </view>
      </view>

      <view class="card">
        <text class="card-title">这里的错题</text>
        <text v-if="detail.mistakes.length === 0" class="hint">还没有错题挂在这个知识点上。</text>
        <view
          v-for="item in detail.mistakes"
          :key="item.mistakeId"
          class="mistake"
          @tap="openMistake(item.mistakeId)"
        >
          <text class="mistake-stem">{{ item.stem }}</text>
          <text class="mistake-meta">{{ item.errorType || "未标错因" }} · {{ formatDate(item.createdAt) }}</text>
        </view>
      </view>

      <view class="ghost" @tap="notReady">
        <text class="ghost-text">练这一块</text>
      </view>
    </template>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-10);
  padding: var(--tt-space-12) var(--tt-space-16) var(--tt-space-32);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-8);
  padding: var(--tt-space-16);
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}
.card-title {
  font-size: var(--tt-text-12);
  font-weight: 600;
  color: var(--tt-on-surface-variant);
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.name {
  font-size: var(--tt-text-19);
  font-weight: 600;
  color: var(--tt-on-surface);
}
.score {
  padding: var(--tt-space-4) var(--tt-space-10);
  border-radius: var(--tt-radius-pill);
  font-size: var(--tt-text-15);
  font-weight: 600;
}
.meta {
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
}
.level {
  font-size: var(--tt-text-13);
  color: var(--tt-on-surface);
}

.hint {
  font-size: var(--tt-text-12);
  color: var(--tt-on-surface-variant);
  line-height: var(--tt-leading-normal);
}

.event {
  display: flex;
  align-items: center;
  gap: var(--tt-space-8);
}
.event-kind {
  flex: 1;
  font-size: var(--tt-text-13);
  color: var(--tt-on-surface);
}
.event-time {
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
}
.event-score {
  font-size: var(--tt-text-12);
  color: var(--tt-on-surface-variant);
}

.mistake {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-4);
  padding: var(--tt-space-10);
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
}
.mistake-stem {
  font-size: var(--tt-text-13);
  color: var(--tt-on-surface);
  line-height: var(--tt-leading-normal);
}
.mistake-meta {
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
}

.ghost {
  align-self: flex-start;
  padding: var(--tt-space-8) var(--tt-space-16);
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.ghost-text {
  font-size: var(--tt-text-13);
  color: var(--tt-on-surface);
}

.heat-red {
  background: var(--tt-error-container);
  color: var(--tt-error);
}
.heat-yellow {
  background: var(--tt-warning-container);
  color: var(--tt-warning);
}
.heat-green {
  background: var(--tt-success-container);
  color: var(--tt-success);
}

.error {
  font-size: var(--tt-text-12);
  color: var(--tt-error);
}
</style>
