<script setup lang="ts">
import { useMistakesStore, type TimeRange } from "@/stores/mistakes";
import { ERROR_TYPES, labelOf, relativeTime, SUBJECTS, type Subject } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";

/**
 * 易错题：一页翻旧账。
 *
 * 内容区第一个控件就是筛选条，上面只有标题栏——没有状态分段、没有"复习"按钮、
 * 没有搜索框。掌握程度不在这里看（去知识点页），所以列表项只给
 * 科目 · 相对时间 · 错因 · 复发次数。
 */
const store = useMistakesStore();

const timeRanges: Array<{ value: TimeRange; label: string }> = [
  { value: 0, label: "全部时间" },
  { value: 7, label: "近 7 天" },
  { value: 30, label: "近 30 天" },
];

onShow(() => {
  void store.load();
});

function openDetail(mistakeId: string): void {
  uni.navigateTo({ url: `/pages/mistakes/detail?id=${encodeURIComponent(mistakeId)}` });
}

function subjectLabel(code: string): string {
  return labelOf(code as Subject);
}

function timeLabel(createdAt: number): string {
  return relativeTime(createdAt, Date.now());
}
</script>

<template>
  <view class="page">
    <!-- 筛选条：内容区第一个控件 -->
    <scroll-view class="filters" scroll-x :show-scrollbar="false">
      <view class="filter-row">
        <view class="chip" :class="{ 'chip-on': store.subject === '' }" @tap="store.setSubject('')">
          <text class="chip-text">全部科目</text>
        </view>
        <view
          v-for="subject in SUBJECTS"
          :key="subject"
          class="chip"
          :class="{ 'chip-on': store.subject === subject }"
          @tap="store.setSubject(subject)"
        >
          <text class="chip-text">{{ labelOf(subject) }}</text>
        </view>
      </view>

      <view class="filter-row">
        <view class="chip" :class="{ 'chip-on': store.errorType === '' }" @tap="store.setErrorType('')">
          <text class="chip-text">全部错因</text>
        </view>
        <view
          v-for="type in ERROR_TYPES"
          :key="type"
          class="chip"
          :class="{ 'chip-on': store.errorType === type }"
          @tap="store.setErrorType(type)"
        >
          <text class="chip-text">{{ type }}</text>
        </view>
      </view>

      <view v-if="store.kpointOptions.length" class="filter-row">
        <view class="chip" :class="{ 'chip-on': store.kpointId === '' }" @tap="store.setKpoint('')">
          <text class="chip-text">全部知识点</text>
        </view>
        <view
          v-for="option in store.kpointOptions"
          :key="option.id"
          class="chip"
          :class="{ 'chip-on': store.kpointId === option.id }"
          @tap="store.setKpoint(option.id)"
        >
          <text class="chip-text">{{ option.name }}</text>
        </view>
      </view>

      <view class="filter-row">
        <view
          v-for="range in timeRanges"
          :key="range.value"
          class="chip"
          :class="{ 'chip-on': store.days === range.value }"
          @tap="store.setDays(range.value)"
        >
          <text class="chip-text">{{ range.label }}</text>
        </view>
      </view>
    </scroll-view>

    <view v-if="store.hasFilter" class="filter-summary">
      <text class="summary-text">筛出 {{ store.total }} 条</text>
      <text class="summary-clear" @tap="store.clearFilters()">清空筛选</text>
    </view>

    <text v-if="store.error" class="error-text">{{ store.error }}</text>

    <view v-if="!store.loading && store.items.length === 0" class="empty">
      <text class="empty-title">还没有错题</text>
      <text class="empty-hint">拍一道题，解答会自动存进来</text>
    </view>

    <view class="list">
      <view
        v-for="item in store.items"
        :key="item.mistakeId"
        class="item"
        @tap="openDetail(item.mistakeId)"
      >
        <view class="item-top">
          <text class="item-meta">
            {{ subjectLabel(item.subject) }} · {{ timeLabel(item.createdAt) }} ·
            {{ item.errorType || "未标错因" }}
            <text v-if="item.recurrenceIndex > 0"> · 复发 {{ item.recurrenceIndex }} 次</text>
          </text>
        </view>
        <text class="item-stem">{{ item.stem }}</text>
        <view class="item-bottom">
          <text v-if="item.kpointName" class="kpoint">{{ item.kpointName }}</text>
          <text class="item-answer">答案 {{ item.finalAnswer }}</text>
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

.filter-summary {
  display: flex;
  justify-content: space-between;
  padding: 0 16px;
}
.summary-text {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}
.summary-clear {
  font-size: 12px;
  color: var(--tt-primary);
}

.empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: 48px 16px;
}
.empty-title {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.empty-hint {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}

.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 14px;
}
.item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.item-top {
  display: flex;
  justify-content: space-between;
}
.item-meta {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.item-stem {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.item-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.kpoint {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--tt-primary);
  background: var(--tt-primary-container);
}
.item-answer {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.error-text {
  padding: 0 16px;
  font-size: 12px;
  color: var(--tt-error);
}
</style>
