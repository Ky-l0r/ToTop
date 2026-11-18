<script setup lang="ts">
// 拍题页骨架。对话、解答呈现与改写内核在 T5 至 T7 落地。
</script>

<template>
  <view class="page">
    <text class="title">拍题</text>
    <text class="hint">骨架页，T5 至 T7 落地</text>
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
