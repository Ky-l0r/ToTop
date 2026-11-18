<script setup lang="ts">
// 陪伴页骨架。删计时、拍照与示例提示，记输入方式，在 T12 落地。
</script>

<template>
  <view class="page">
    <text class="title">陪伴</text>
    <text class="hint">骨架页，T12 落地</text>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-8);
  padding: var(--tt-space-24) var(--tt-space-16);
}
.title {
  font-size: var(--tt-text-22);
  font-weight: 600;
}
.hint {
  font-size: var(--tt-text-13);
  opacity: 0.55;
}
</style>
