<script setup lang="ts">
import { MAX_PAGES } from "@/platform/image";
import { useSolveStore, type TeachingMode } from "@/stores/solve";
import { onMounted } from "vue";

/**
 * 拍题页（1.0 第二批：接上拍照与相册）。
 *
 * 按指南 5.3 已落实：AI 概括作标题、模式双段放大、输入区精简、多页图片、
 * 结论/步骤/思路/错因/知识点、错误分档与重试。
 * 还没做：选项逐行、单多选确认、抓拍裁剪、追问、写库（T6 至 T9）。
 */
const store = useSolveStore();

const modes: Array<{ value: TeachingMode; label: string; hint: string }> = [
  { value: "DIRECT", label: "直接讲解", hint: "给完整步骤与答案" },
  { value: "GUIDED", label: "提示引导", hint: "先给方向，卡住再要提示" },
];

onMounted(() => {
  void store.loadConfig();
});
</script>

<template>
  <view class="page">
    <view class="header">
      <text class="title">{{ store.title }}</text>
      <text v-if="store.stageLabel" class="stage">{{ store.stageLabel }}</text>
    </view>

    <!-- 模式：两档做大并带一句说明。选中态是填充色，不是小胶囊。 -->
    <view class="modes">
      <view
        v-for="item in modes"
        :key="item.value"
        class="mode"
        :class="{ 'mode-on': store.mode === item.value }"
        @tap="store.setMode(item.value)"
      >
        <text class="mode-label">{{ item.label }}</text>
        <text class="mode-hint">{{ item.hint }}</text>
      </view>
    </view>

    <!-- 结果区 -->
    <view v-if="store.outcome" class="card">
      <view class="answer">
        <text class="answer-label">答案</text>
        <text class="answer-text">{{ store.outcome.solution.finalAnswer }}</text>
      </view>

      <view class="block">
        <text class="block-title">步骤</text>
        <view v-for="step in store.outcome.solution.steps" :key="step.idx" class="step">
          <text class="step-no">{{ step.idx }}</text>
          <view class="step-body">
            <text class="step-content">{{ step.content }}</text>
            <text v-if="step.why" class="step-why">{{ step.why }}</text>
          </view>
        </view>
      </view>

      <!-- 错因只指向第一个出错的地方，不给一堆泛泛的评价 -->
      <view v-if="store.outcome.report?.firstError" class="block error">
        <text class="block-title">第一处出错</text>
        <text class="error-head">
          第 {{ store.outcome.report.firstError.stepIdx }} 步 ·
          {{ store.outcome.report.firstError.type }}
        </text>
        <text class="error-text">{{ store.outcome.report.firstError.explain }}</text>
        <text v-if="store.outcome.report.firstError.correctThinking" class="error-next">
          下次这样想：{{ store.outcome.report.firstError.correctThinking }}
        </text>
      </view>

      <view v-if="store.outcome.kpoints?.kpoints.length" class="block">
        <text class="block-title">这题考的是</text>
        <view class="chips">
          <text v-for="kpoint in store.outcome.kpoints.kpoints" :key="kpoint.id" class="chip">
            {{ kpoint.name }}
          </text>
        </view>
      </view>

      <text class="usage">
        本次用量 {{ store.usage.inputTokens }} + {{ store.usage.outputTokens }} tokens
      </text>
    </view>

    <!-- 失败：说清是什么问题，再给重试 -->
    <view v-if="store.failure" class="card failure">
      <text class="failure-text">{{ store.failure.message }}</text>
      <view class="failure-actions">
        <view class="ghost" @tap="store.send()">重试</view>
        <view class="ghost" @tap="store.reset()">清空</view>
      </view>
    </view>

    <!-- 流式片段：非流式传输下通常为空，只在能收到 chunk 时出现 -->
    <view v-if="store.running && store.streamText" class="card">
      <text class="stream">{{ store.streamText }}</text>
    </view>

    <!-- 输入区 -->
    <view class="composer">
      <!-- 拍好的图片在输入框上方：先看拍了什么，再决定要不要补拍 -->
      <view v-if="store.images.length" class="shots">
        <view v-for="(shot, index) in store.images" :key="shot.sha256" class="shot">
          <image :src="shot.path" class="shot-img" mode="aspectFill" />
          <view class="shot-del" @tap="store.removeImage(index)">
            <text class="shot-del-text">×</text>
          </view>
        </view>
      </view>

      <view class="shot-actions">
        <view class="ghost" @tap="store.addImages('camera')">拍照</view>
        <view class="ghost" @tap="store.addImages('album')">相册</view>
        <text class="tip">
          {{ store.preparing ? "正在处理图片…" : `最多 ${MAX_PAGES} 张，同一道题的多页` }}
        </text>
      </view>

      <textarea
        v-model="store.draft"
        class="input"
        :maxlength="2000"
        placeholder="可以补一句你的思路，或者卡在哪"
        auto-height
      />

      <text v-if="store.imageError" class="failure-text">{{ store.imageError }}</text>

      <view class="composer-row">
        <text v-if="!store.configured" class="tip">先在下面填好模型地址与 Key</text>
        <text v-else class="tip">一条只解一道题</text>
        <view v-if="store.running" class="send stop" @tap="store.stop()">
          <text class="send-text">停止</text>
        </view>
        <view v-else class="send" :class="{ 'send-off': !store.canSend }" @tap="store.send()">
          <text class="send-text">发送</text>
        </view>
      </view>
    </view>

    <!-- 配置：Key 不落盘，界面必须说清楚 -->
    <view class="card config">
      <text class="block-title">模型设置</text>
      <input v-model="store.baseUrl" class="field" placeholder="接口地址，如 https://api.deepseek.com/v1" />
      <input v-model="store.model" class="field" placeholder="模型名，如 deepseek-chat" />
      <input v-model="store.apiKey" class="field" password placeholder="API Key（只存在本次运行内）" />
      <text class="tip">Key 不写入任何存储，关掉应用要重填；系统钥匙串在 T12 接入。</text>
      <view class="ghost" @tap="store.saveEndpoint()">保存地址与模型</view>
      <text v-if="store.configError" class="failure-text">{{ store.configError }}</text>
    </view>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 14px 28px;
}

.header {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.title {
  font-size: 19px;
  font-weight: 600;
  color: var(--tt-on-surface);
}
.stage {
  font-size: 12px;
  color: var(--tt-primary);
}

.modes {
  display: flex;
  gap: 8px;
}
.mode {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-height: 44px;
  padding: 10px 12px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-surface-variant);
  border: 1px solid transparent;
}
.mode-on {
  background: var(--tt-primary-container);
  border-color: var(--tt-primary);
}
.mode-label {
  font-size: 15px;
  font-weight: 600;
  color: var(--tt-on-surface);
}
.mode-on .mode-label {
  color: var(--tt-primary);
}
.mode-hint {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}

.answer {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.answer-label {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
.answer-text {
  font-size: 18px;
  font-weight: 600;
  color: var(--tt-primary);
}

.block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--tt-on-surface-variant);
}

.step {
  display: flex;
  gap: 8px;
}
.step-no {
  width: 18px;
  font-size: 12px;
  color: var(--tt-primary);
  text-align: center;
}
.step-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.step-content {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.55;
}
/* 思路比结论小一号、换个颜色：扫读时先看结论，需要时才读依据 */
.step-why {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.error {
  padding: 10px;
  border-radius: var(--tt-radius-md);
  background: var(--tt-error-container);
}
.error .block-title {
  color: var(--tt-error);
}
.error-head {
  font-size: 12px;
  color: var(--tt-error);
}
.error-text {
  font-size: 14px;
  color: var(--tt-on-surface);
  line-height: 1.55;
}
.error-next {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--tt-primary);
  background: var(--tt-primary-container);
}

.usage {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}

.failure {
  background: var(--tt-error-container);
}
.failure-text {
  font-size: 13px;
  color: var(--tt-error);
  line-height: 1.5;
}
.failure-actions {
  display: flex;
  gap: 8px;
}

.ghost {
  align-self: flex-start;
  padding: 6px 12px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
  font-size: 13px;
  color: var(--tt-on-surface);
}

.stream {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}

.shots {
  display: flex;
  gap: 8px;
}
.shot {
  position: relative;
  width: 68px;
  height: 68px;
}
.shot-img {
  width: 68px;
  height: 68px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
}
.shot-del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--tt-on-surface);
  display: flex;
  align-items: center;
  justify-content: center;
}
.shot-del-text {
  font-size: 13px;
  line-height: 1;
  color: #ffffff;
}
.shot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.shot-actions .tip {
  flex: 1;
  text-align: right;
}
.input {
  width: 100%;
  min-height: 44px;
  max-height: 140px;
  font-size: 14px;
  color: var(--tt-on-surface);
}
.composer-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}
.tip {
  flex: 1;
  font-size: 11px;
  color: var(--tt-on-surface-variant);
}
/* 发送键与输入框底边对齐，单行时也不上浮 */
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
.stop {
  background: var(--tt-error);
}
.send-text {
  font-size: 14px;
  color: #ffffff;
}

.config {
  gap: 8px;
}
.field {
  width: 100%;
  height: 38px;
  padding: 0 10px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
  border: 1px solid var(--tt-outline);
  font-size: 13px;
  color: var(--tt-on-surface);
}
</style>
