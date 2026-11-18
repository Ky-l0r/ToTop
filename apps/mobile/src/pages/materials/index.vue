<script setup lang="ts">
import { useMaterialsStore } from "@/stores/materials";
import { labelOf, relativeTime, SUBJECTS, type Subject } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";

/**
 * 资料库。
 *
 * 只有一种来源：导入的标准答案。拍题解答不进这里——
 * 两种东西混放之后，学生分不清哪份是老师给的、哪份是 AI 算的，而这两者可信度完全不同。
 */
const store = useMaterialsStore();

onShow(() => {
  void store.load();
});

function openDetail(id: string): void {
  uni.navigateTo({ url: `/pages/materials/detail?id=${encodeURIComponent(id)}` });
}

function when(utc: number): string {
  return relativeTime(utc, Date.now());
}
</script>

<template>
  <view class="page">
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
    </scroll-view>

    <view class="actions">
      <view class="primary" @tap="store.importFrom('camera')">
        <text class="primary-text">{{ store.importing ? "正在读…" : "拍一页标准答案" }}</text>
      </view>
      <view class="ghost" @tap="store.importFrom('album')">
        <text class="ghost-text">从相册选</text>
      </view>
    </view>

    <text v-if="store.lastMessage" class="ok">{{ store.lastMessage }}</text>
    <text v-if="store.error" class="error">{{ store.error }}</text>

    <view v-if="!store.loading && store.items.length === 0" class="empty">
      <text class="empty-title">还没有资料</text>
      <text class="empty-hint">把老师发的标准答案导进来，这里就会攒起来</text>
    </view>

    <!-- 一卡一条，不做列表行 -->
    <view class="cards">
      <view
        v-for="item in store.items"
        :key="item.id"
        class="card"
        @tap="openDetail(item.id)"
      >
        <view class="card-head">
          <text class="card-title">{{ item.title }}</text>
          <text class="card-time">{{ when(item.createdAtUtc) }}</text>
        </view>
        <text class="card-answer">{{ item.answerExcerpt }}</text>
        <view class="card-foot">
          <text class="tag">{{ labelOf(item.subject as Subject) }}</text>
          <text v-if="item.kpointName" class="tag">{{ item.kpointName }}</text>
          <text class="tag tag-quiet">{{ item.origin }}</text>
          <text v-if="item.hasExplanation" class="tag tag-ai">已讲一遍</text>
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

.actions {
  display: flex;
  gap: 8px;
  padding: 0 14px;
}
.primary {
  padding: 9px 16px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-primary);
}
.primary-text {
  font-size: 14px;
  color: #ffffff;
}
.ghost {
  padding: 9px 16px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.ghost-text {
  font-size: 14px;
  color: var(--tt-on-surface);
}

.cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 14px;
}
.card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}
.card-title {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
  color: var(--tt-on-surface);
  line-height: 1.4;
}
.card-time {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
  flex-shrink: 0;
}
/* 答案首段限两行：卡片高度要可预期，不然一屏里长短差太多 */
.card-answer {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.card-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  color: var(--tt-primary);
  background: var(--tt-primary-container);
}
.tag-quiet {
  color: var(--tt-on-surface-variant);
  background: var(--tt-surface);
}
.tag-ai {
  color: var(--tt-success);
  background: var(--tt-success-container);
}

.empty {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: 44px 16px;
}
.empty-title {
  font-size: 15px;
  color: var(--tt-on-surface);
}
.empty-hint {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}

.ok {
  padding: 0 16px;
  font-size: 12px;
  color: var(--tt-success);
}
.error {
  padding: 0 16px;
  font-size: 12px;
  color: var(--tt-error);
}
</style>
