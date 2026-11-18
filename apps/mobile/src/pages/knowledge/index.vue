<script setup lang="ts">
import { useKnowledgeStore } from "@/stores/knowledge";
import { heatOf, labelOf, type MasteryBand, type Subject } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";

/**
 * 知识点。
 *
 * 这一页回答一个问题："哪块不熟，先攻它"。
 * 所以没有雷达、没有折线、没有饼图——三种图形都在讲"趋势"，而学生现在要的是"名单"。
 * 唯一保留的可视化是章节热力：一排色块，一眼看出哪一章在红。
 */
const store = useKnowledgeStore();

const bands: Array<{ value: MasteryBand; label: string }> = [
  { value: "all", label: "全部" },
  { value: "weak", label: "不熟" },
  { value: "mid", label: "一般" },
  { value: "strong", label: "已掌握" },
];

onShow(() => {
  void store.load();
});

function percent(score: number): string {
  return `${Math.round(score * 100)}%`;
}

function heatClass(score: number): string {
  return `heat-${heatOf(score)}`;
}

function openDetail(kpointId: string): void {
  uni.navigateTo({ url: `/pages/knowledge/detail?id=${encodeURIComponent(kpointId)}` });
}

function notReady(): void {
  uni.showToast({ title: "巩固练习在下一批", icon: "none" });
}
</script>

<template>
  <view class="page">
    <!-- 唯一一套筛选 -->
    <scroll-view class="filters" scroll-x :show-scrollbar="false">
      <view class="filter-row">
        <view class="chip" :class="{ 'chip-on': store.subject === '' }" @tap="store.setSubject('')">
          <text class="chip-text">全部科目</text>
        </view>
        <view
          v-for="subject in store.subjectOptions"
          :key="subject"
          class="chip"
          :class="{ 'chip-on': store.subject === subject }"
          @tap="store.setSubject(subject)"
        >
          <text class="chip-text">{{ labelOf(subject as Subject) }}</text>
        </view>
      </view>
      <view class="filter-row">
        <view
          v-for="item in bands"
          :key="item.value"
          class="chip"
          :class="{ 'chip-on': store.band === item.value }"
          @tap="store.setBand(item.value)"
        >
          <text class="chip-text">{{ item.label }}</text>
        </view>
      </view>
    </scroll-view>

    <text v-if="store.error" class="error">{{ store.error }}</text>

    <!-- 章节热力：红 <40 / 黄 40-70 / 绿 >70 -->
    <view v-if="store.heat.length" class="heat">
      <view v-for="chapter in store.heat" :key="chapter.chapter" class="heat-cell">
        <view class="heat-bar" :class="heatClass(chapter.score)" />
        <text class="heat-name">{{ chapter.chapter }}</text>
        <text class="heat-value">{{ percent(chapter.score) }}</text>
      </view>
    </view>

    <view v-if="!store.loading && store.items.length === 0" class="empty">
      <text class="empty-title">这个范围里还没有知识点</text>
      <text class="empty-hint">有错题被关联到知识点后，这里会长出来</text>
    </view>

    <view class="list">
      <view
        v-for="item in store.items"
        :key="item.id"
        class="item"
        @tap="openDetail(item.id)"
      >
        <view class="item-main">
          <text class="item-name">{{ item.name }}</text>
          <text class="item-meta">
            {{ item.chapter }} · 错 {{ item.mistakeCount }} 次
            <text v-if="!item.hasMastery"> · 还没练过</text>
          </text>
        </view>
        <view class="item-right">
          <text class="item-score" :class="heatClass(item.score)">{{ percent(item.score) }}</text>
          <view class="practice" @tap.stop="notReady">
            <text class="practice-text">练</text>
          </view>
        </view>
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

.filters {
  width: 100%;
  white-space: nowrap;
}
.filter-row {
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

.heat {
  display: flex;
  gap: 6px;
  padding: 0 14px;
  overflow-x: auto;
}
.heat-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: center;
  min-width: 56px;
}
.heat-bar {
  width: 100%;
  height: 6px;
  border-radius: 3px;
}
.heat-name {
  font-size: 10px;
  color: var(--tt-on-surface-variant);
}
.heat-value {
  font-size: 10px;
  color: var(--tt-on-surface-variant);
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

.list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 14px;
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.item-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.item-name {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.item-meta {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.item-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
/* 分数用色块表示档位，和章节热力同一套颜色 */
.item-score {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.practice {
  padding: 4px 12px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.practice-text {
  font-size: 12px;
  color: var(--tt-on-surface);
}

.empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.error {
  padding: 0 16px;
  font-size: 12px;
  color: var(--tt-error);
}
</style>
