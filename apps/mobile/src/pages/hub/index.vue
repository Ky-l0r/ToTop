<script setup lang="ts">
// 功能中心骨架。七格入口与改名在 T8 落地；已经做出来的页面先挂一个入口，免得做完没法点。
const entries: Array<{ label: string; url: string }> = [
  { label: "易错题", url: "/pages/mistakes/index" },
];

function open(url: string): void {
  uni.navigateTo({ url });
}
</script>

<template>
  <view class="page">
    <text class="title">全部功能</text>
    <text class="hint">七格入口在 T8 落地，先挂已完成的页面</text>

    <view class="entries">
      <view v-for="entry in entries" :key="entry.url" class="entry" @tap="open(entry.url)">
        <text class="entry-label">{{ entry.label }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 24px 16px;
}
.title {
  font-size: 22px;
  font-weight: 600;
}
.hint {
  font-size: 13px;
  opacity: 0.55;
}
.entries {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.entry {
  padding: 14px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
}
.entry-label {
  font-size: 15px;
  color: var(--tt-on-surface);
}
</style>
