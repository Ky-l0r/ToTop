<script setup lang="ts">
// 首页骨架。内容与信息架构在 T8 落地（删标语与拍题卡，留不熟知识点 / 近期学情 / 常用功能）。
</script>

<template>
  <view class="page">
    <text class="title">首页</text>
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
