<script setup lang="ts">
/**
 * 功能中心。
 *
 * 七格：易错题、知识点、巩固练习、资料库、复习资料、学情、设置（三列，最后一行一格）。
 * 名字全部按 1.0 口径：没有"复习打卡""多练的知识点""卡点笔记"。
 *
 * 还没做的入口显示为禁用，而不是点进去看到白屏——"每个入口都有页面"这条验收
 * 要等 T10 至 T12，在那之前不假装可用。
 */
interface Entry {
  label: string;
  hint: string;
  url?: string;
}

const entries: Entry[] = [
  { label: "易错题", hint: "按科目、错因、时间翻旧账", url: "/pages/mistakes/index" },
  { label: "知识点", hint: "哪块不熟，一目了然", url: "/pages/knowledge/index" },
  { label: "巩固练习", hint: "对着不熟的点练几道" },
  { label: "资料库", hint: "标准答案收在这一处", url: "/pages/materials/index" },
  { label: "复习资料", hint: "按知识点整成一份" },
  { label: "学情", hint: "成绩与趋势", url: "/pages/exams/index" },
  { label: "设置", hint: "模型、科目、数据", url: "/pages/settings/index" },
];

function open(entry: Entry): void {
  if (!entry.url) return;
  uni.navigateTo({ url: entry.url });
}
</script>

<template>
  <view class="page">
    <view class="grid">
      <view
        v-for="entry in entries"
        :key="entry.label"
        class="cell"
        :class="{ 'cell-off': !entry.url }"
        @tap="open(entry)"
      >
        <text class="cell-label">{{ entry.label }}</text>
        <text class="cell-hint">{{ entry.hint }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  padding: var(--tt-space-16);
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tt-space-8);
}
.cell {
  /* 同上：不加 border-box，padding 会把每格撑过 33%，三列掉成两列 */
  box-sizing: border-box;
  width: calc(33.333% - 6px);
  min-height: 78px;
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-4);
  padding: var(--tt-space-12) var(--tt-space-10);
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.cell-off {
  opacity: 0.45;
}
.cell-label {
  font-size: var(--tt-text-15);
  font-weight: 600;
  color: var(--tt-on-surface);
}
.cell-hint {
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
  line-height: var(--tt-leading-tight);
}
</style>
