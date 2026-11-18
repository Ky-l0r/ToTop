<script setup lang="ts">
import { useHomeStore } from "@/stores/home";
import { labelOf, type Subject } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";
import { computed } from "vue";

/**
 * 首页。
 *
 * 一屏看完"我最近哪儿不熟 + 最近考得怎么样 + 我要去哪儿"。
 * 没有标语区、没有"拍一道不会的题"大卡——拍题是底部 tab，首页再放一张是重复。
 * 顶部第一行就是内容：不熟知识点，或者学情数字。
 */
const store = useHomeStore();

const weak = computed(() => store.weak);
const stats = computed(() => store.stats);

/** 不熟知识点为空时整块不占位，不留空标题。 */
const hasWeak = computed(() => weak.value.length > 0);

const examLabel = computed(() => {
  const exam = stats.value?.latestExam;
  if (!exam) return "还没有成绩";
  return `${Math.round(exam.rate * 100)}%`;
});

const examHint = computed(() => {
  const exam = stats.value?.latestExam;
  if (!exam) return "去学情里补一次考试";
  return `${labelOf(exam.subject as Subject)} ${exam.score}/${exam.fullScore}`;
});

onShow(() => {
  void store.load();
});

function openWeakKpoint(kpointId: string): void {
  uni.navigateTo({ url: `/pages/mistakes/index?kpointId=${encodeURIComponent(kpointId)}` });
}

function go(url: string): void {
  uni.navigateTo({ url });
}

/** 底部 tab 上的页面只能用 switchTab 进，用 navigateTo 会静默失败。 */
function goTab(url: string): void {
  uni.switchTab({ url });
}
</script>

<template>
  <view class="page">
    <!-- 骨架屏：数据到位前先占住结构，避免"空了一下再跳出来" -->
    <template v-if="store.loading">
      <view class="skeleton-block">
        <view class="skeleton-line wide" />
        <view class="skeleton-line" />
        <view class="skeleton-line short" />
      </view>
      <view class="skeleton-block">
        <view class="skeleton-line wide" />
        <view class="skeleton-line short" />
      </view>
    </template>

    <template v-else>
      <text v-if="store.error" class="error-text">{{ store.error }}</text>

      <!-- 最近不熟的知识点 -->
      <view v-if="hasWeak" class="section">
        <view class="section-head">
          <text class="section-title">最近不熟的知识点</text>
          <text class="section-more" @tap="go('/pages/mistakes/index')">看易错题</text>
        </view>
        <view class="weak-list">
          <view
            v-for="item in weak"
            :key="item.id"
            class="weak-item"
            @tap="openWeakKpoint(item.id)"
          >
            <view class="weak-main">
              <text class="weak-name">{{ item.name }}</text>
              <text class="weak-meta">
                {{ labelOf(item.subject as Subject) }} ·
                <text v-if="item.mistakeCount > 0">错 {{ item.mistakeCount }} 次</text>
                <text v-if="item.mistakeCount > 0 && item.askCount > 0"> · </text>
                <text v-if="item.askCount > 0">问过 {{ item.askCount }} 次</text>
              </text>
            </view>
            <text class="weak-arrow">›</text>
          </view>
        </view>
      </view>

      <!-- 近期学情：三个数字，口径与易错题页、学情页一致 -->
      <view class="section">
        <view class="section-head">
          <text class="section-title">近期学情</text>
        </view>
        <view class="stats">
          <view class="stat">
            <text class="stat-value">{{ stats?.mistakesLast7Days ?? 0 }}</text>
            <text class="stat-label">近 7 天错题</text>
          </view>
          <view class="stat">
            <text class="stat-value">{{ examLabel }}</text>
            <text class="stat-label">{{ examHint }}</text>
          </view>
          <view class="stat">
            <text class="stat-value">{{ stats?.masteredKpoints ?? 0 }}</text>
            <text class="stat-label">已掌握知识点</text>
          </view>
        </view>
      </view>

      <!-- 常用功能：四格。放"陪伴作业"是重复，它是底部 tab。 -->
      <view class="section">
        <text class="section-title">常用功能</text>
        <view class="grid">
          <view class="grid-item" @tap="go('/pages/mistakes/index')">
            <text class="grid-label">易错题</text>
            <text class="grid-hint">翻旧账</text>
          </view>
          <view class="grid-item grid-off">
            <text class="grid-label">知识点</text>
            <text class="grid-hint">T10 上线</text>
          </view>
          <view class="grid-item" @tap="go('/pages/exams/index')">
            <text class="grid-label">学情</text>
            <text class="grid-hint">成绩与趋势</text>
          </view>
          <view class="grid-item" @tap="goTab('/pages/hub/index')">
            <text class="grid-label">全部功能</text>
            <text class="grid-hint">七个入口</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px;
}

.section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--tt-on-surface-variant);
}
.section-more {
  font-size: 12px;
  color: var(--tt-primary);
}

.weak-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.weak-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.weak-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.weak-name {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.weak-meta {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.weak-arrow {
  font-size: 18px;
  color: var(--tt-outline-strong);
}

.stats {
  display: flex;
  gap: 8px;
}
.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-primary-container);
}
.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: var(--tt-primary);
}
.stat-label {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.grid-item {
  width: calc(50% - 4px);
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 14px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.grid-off {
  opacity: 0.45;
}
.grid-label {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.grid-hint {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.skeleton-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.skeleton-line {
  height: 12px;
  width: 60%;
  border-radius: 6px;
  background: var(--tt-outline);
}
.skeleton-line.wide {
  width: 90%;
}
.skeleton-line.short {
  width: 35%;
}

.error-text {
  font-size: 12px;
  color: var(--tt-error);
}
</style>
