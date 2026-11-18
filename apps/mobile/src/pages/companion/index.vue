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
</style>
