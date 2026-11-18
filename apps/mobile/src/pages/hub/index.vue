<script setup lang="ts">
// 功能中心骨架。七格入口与改名在 T8 落地，二级页面各自在 T9 至 T12 落地。
</script>

<template>
  <view class="page">
    <text class="title">全部功能</text>
    <text class="hint">骨架页，T8 落地</text>
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
