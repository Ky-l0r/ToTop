<script setup lang="ts">
import { useLlmStore } from "@/stores/llm";
import { useCompanionStore } from "@/stores/companion";
import { onMounted } from "vue";

/**
 * 陪伴页：嘴上问一句。
 *
 * 没有计时、没有相机按钮、没有示例问句——三样都删了。这一页只做一件事：
 * 学生写作业卡住，问一句，拿到能让他继续写下去的一句话。
 *
 * 页头是一句用法说明，不是"陪伴 + 已写 34 分钟"。
 */
const store = useCompanionStore();
const llm = useLlmStore();

onMounted(() => {
  void llm.load();
});
</script>

<template>
  <view class="page">
    <view class="header">
      <text class="title">问一句</text>
      <text class="hint">卡住的时候问一句，不用把题抄全；拍整题请去拍题页</text>
    </view>

    <scroll-view class="thread" scroll-y :scroll-into-view="`bubble-${store.bubbles.length - 1}`">
      <view v-if="store.bubbles.length === 0" class="empty">
        <text class="empty-text">比如：这一步为什么要配方？</text>
      </view>

      <view
        v-for="bubble in store.bubbles"
        :id="`bubble-${bubble.id}`"
        :key="bubble.id"
        class="row"
        :class="bubble.role === 'user' ? 'row-user' : 'row-assistant'"
      >
        <view class="bubble" :class="[bubble.role === 'user' ? 'bubble-user' : 'bubble-assistant', bubble.failed ? 'bubble-failed' : '']">
          <text v-if="bubble.pending" class="pending">正在想…</text>
          <text v-else class="bubble-text">{{ bubble.content }}</text>
        </view>
        <!-- 失败只在原气泡里重发，不新增一条 -->
        <text v-if="bubble.failed" class="retry" @tap="store.retry(bubble.id)">重试这一问</text>
      </view>
    </scroll-view>

    <view class="composer">
      <textarea
        v-model="store.draft"
        class="input"
        :maxlength="500"
        placeholder="问一句，不用写全"
        auto-height
      />
      <view class="composer-row">
        <text v-if="!llm.configured" class="tip">还没配置模型，去设置里填地址与 Key</text>
        <text v-else class="tip">最多记住最近 4 轮</text>
        <view class="send" :class="{ 'send-off': !store.canSend }" @tap="store.send()">
          <text class="send-text">{{ store.sending ? "…" : "发送" }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  padding: var(--tt-space-12) var(--tt-space-16);
  box-sizing: border-box;
}

.header {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-2);
  padding-bottom: var(--tt-space-10);
}
.title {
  font-size: var(--tt-text-19);
  font-weight: 600;
  color: var(--tt-on-surface);
}
.hint {
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
}

.thread {
  flex: 1;
  overflow: hidden;
}

.empty {
  padding: var(--tt-space-32) 0;
  display: flex;
  justify-content: center;
}
.empty-text {
  font-size: var(--tt-text-12);
  color: var(--tt-on-surface-variant);
}

.row {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-4);
  margin-bottom: var(--tt-space-10);
}
.row-user {
  align-items: flex-end;
}
.row-assistant {
  align-items: flex-start;
}

.bubble {
  max-width: 82%;
  padding: var(--tt-space-10) var(--tt-space-12);
  border-radius: var(--tt-radius-md);
}
.bubble-user {
  background: var(--tt-primary);
}
.bubble-user .bubble-text {
  color: var(--tt-on-primary);
}
.bubble-assistant {
  background: var(--tt-surface-variant);
}
.bubble-failed {
  background: var(--tt-error-container);
}
.bubble-failed .bubble-text {
  color: var(--tt-error);
}
.bubble-text {
  font-size: var(--tt-text-14);
  color: var(--tt-on-surface);
  line-height: var(--tt-leading-relaxed);
}
.pending {
  font-size: var(--tt-text-12);
  color: var(--tt-on-surface-variant);
}
.retry {
  padding-left: var(--tt-space-4);
  font-size: var(--tt-text-12);
  color: var(--tt-primary);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: var(--tt-space-8);
  padding: var(--tt-space-10) var(--tt-space-12);
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}
.input {
  width: 100%;
  min-height: 44px;
  font-size: var(--tt-text-14);
  color: var(--tt-on-surface);
}
.composer-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--tt-space-8);
}
.tip {
  flex: 1;
  font-size: var(--tt-text-11);
  color: var(--tt-on-surface-variant);
}
.send {
  min-width: 72px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-primary);
}
.send-off {
  background: var(--tt-outline-strong);
}
.send-text {
  font-size: var(--tt-text-14);
  color: var(--tt-on-primary);
}
</style>
