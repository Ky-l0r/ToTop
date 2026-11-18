<script setup lang="ts">
import { useLlmStore } from "@/stores/llm";
import { useSettingsStore, type PerformanceTier } from "@/stores/settings";
import { CORE_VERSION, labelOf, SUBJECTS } from "@totop/core";
import { onShow } from "@dcloudio/uni-app";
import { computed } from "vue";
import { PROMPTS } from "@/solve/prompts";

/**
 * 设置。
 *
 * 每一项都必须对应一个真实能力：卡点笔记与复习打卡已经下线，相关的开关不在这里出现。
 * 模型三联（地址 / 模型名 / Key）放在最上面，因为它是唯一"填错就什么都做不了"的一组。
 */
const llm = useLlmStore();
const settings = useSettingsStore();

const tiers: Array<{ value: PerformanceTier; label: string; hint: string }> = [
  { value: "auto", label: "自动", hint: "按机型与网络自己选" },
  { value: "high", label: "高档", hint: "图片 1600 长边，识别更准" },
  { value: "saver", label: "省电", hint: "图片 1280 长边，省流量" },
];

const importedText = computed(() => {
  if (!settings.importedAt) return "还没导入过旧数据";
  const date = new Date(settings.importedAt);
  return `上次导入：${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
});

const promptVersion = PROMPTS.version;

/** 首屏耗时：从模块加载到首页数据到位，真机验收就靠这个数。 */
const firstContentText = computed(() => {
  const ms = settings.firstContentMs;
  if (!ms) return "还没记录";
  return `${(ms / 1000).toFixed(2)} 秒`;
});

onShow(() => {
  void llm.load();
  void settings.load();
});

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

async function saveModel(): Promise<void> {
  const ok = await llm.save();
  uni.showToast({ title: ok ? "已保存" : "地址与模型没保存成功", icon: ok ? "success" : "none" });
}

function pickTier(next: PerformanceTier): void {
  void settings.setTier(next);
}

function toggleSubject(subject: string): void {
  void settings.toggleSubject(subject);
}

function isPicked(subject: string): boolean {
  return settings.subjects.includes(subject);
}

function notReady(): void {
  uni.showToast({ title: "等 T13 接入", icon: "none" });
}
</script>

<template>
  <view class="page">
    <!-- 模型 -->
    <view class="card">
      <text class="card-title">模型</text>
      <input v-model="llm.baseUrl" class="field" placeholder="接口地址，如 https://api.deepseek.com/v1" />
      <input v-model="llm.model" class="field" placeholder="模型名，如 deepseek-chat" />
      <input v-model="llm.apiKey" class="field" password placeholder="API Key（只在本次运行内有效）" />
      <text class="hint">
        Key 不写入任何存储：系统钥匙串要等原生插件，在那之前落盘就是明文。
        关掉应用需要重填，这一条在钥匙串接入前不会变。
      </text>
      <text class="hint">1.0 起每次拍题都会调用一次视觉模型，比 0.8.0 的本地识别更贵也更准。</text>
      <view class="ghost" @tap="saveModel">保存地址与模型</view>
      <text v-if="llm.configError" class="error">{{ llm.configError }}</text>
    </view>

    <!-- 性能档位 -->
    <view class="card">
      <text class="card-title">性能档位</text>
      <view class="tiers">
        <view
          v-for="item in tiers"
          :key="item.value"
          class="tier"
          :class="{ 'tier-on': settings.tier === item.value }"
          @tap="pickTier(item.value)"
        >
          <text class="tier-label">{{ item.label }}</text>
          <text class="tier-hint">{{ item.hint }}</text>
        </view>
      </view>
      <text class="hint">切换后下一张图就按新档位处理，不用重启。</text>
    </view>

    <!-- 科目 -->
    <view class="card">
      <text class="card-title">科目</text>
      <view class="subjects">
        <view
          v-for="subject in SUBJECTS"
          :key="subject"
          class="subject"
          :class="{ 'subject-on': isPicked(subject) }"
          @tap="toggleSubject(subject)"
        >
          <text class="subject-text">{{ labelOf(subject) }}</text>
        </view>
      </view>
      <text class="hint">只影响知识点与练习的范围，不影响已经存下的错题。</text>
    </view>

    <!-- 数据 -->
    <view class="card">
      <text class="card-title">数据</text>
      <text class="hint">{{ importedText }}</text>
      <view class="ghost ghost-off" @tap="notReady">
        <text class="ghost-text">从 0.8.0 备份导入</text>
      </view>
      <text class="hint">解 zip 与图片落盘在 T13 一起做，现在点了不会动数据。</text>
    </view>

    <!-- 关于 -->
    <view class="card">
      <text class="card-title">关于</text>
      <view class="about-row">
        <text class="about-key">应用</text>
        <text class="about-value">To Top 1.0（alpha）</text>
      </view>
      <view class="about-row">
        <text class="about-key">领域层</text>
        <text class="about-value">{{ CORE_VERSION }}</text>
      </view>
      <view class="about-row">
        <text class="about-key">提示词</text>
        <text class="about-value">{{ promptVersion }}</text>
      </view>
      <view class="about-row">
        <text class="about-key">库结构</text>
        <text class="about-value">v{{ settings.schemaVersion }}</text>
      </view>
      <view class="about-row">
        <text class="about-key">上次首屏</text>
        <text class="about-value">{{ firstContentText }}</text>
      </view>
    </view>

    <text v-if="settings.error" class="error">{{ settings.error }}</text>
  </view>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 28px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border-radius: var(--tt-radius-lg);
  background: var(--tt-surface-variant);
}
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--tt-on-surface);
}

.field {
  width: 100%;
  height: 40px;
  padding: 0 10px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
  border: 1px solid var(--tt-outline);
  font-size: 13px;
  color: var(--tt-on-surface);
}

.hint {
  font-size: 11px;
  color: var(--tt-on-surface-variant);
  line-height: 1.5;
}

.tiers {
  display: flex;
  gap: 6px;
}
.tier {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 9px 8px;
  border-radius: var(--tt-radius-sm);
  background: var(--tt-surface);
  border: 1px solid transparent;
}
.tier-on {
  border-color: var(--tt-primary);
  background: var(--tt-primary-container);
}
.tier-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--tt-on-surface);
}
.tier-on .tier-label {
  color: var(--tt-primary);
}
.tier-hint {
  font-size: 10px;
  color: var(--tt-on-surface-variant);
  line-height: 1.4;
}

.subjects {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.subject {
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--tt-surface);
  border: 1px solid transparent;
}
.subject-on {
  background: var(--tt-primary-container);
  border-color: var(--tt-primary);
}
.subject-text {
  font-size: 13px;
  color: var(--tt-on-surface-variant);
}
.subject-on .subject-text {
  color: var(--tt-primary);
  font-weight: 600;
}

.ghost {
  align-self: flex-start;
  padding: 7px 14px;
  border-radius: var(--tt-radius-sm);
  border: 1px solid var(--tt-outline-strong);
}
.ghost-off {
  opacity: 0.45;
}
.ghost-text {
  font-size: 13px;
  color: var(--tt-on-surface);
}

.about-row {
  display: flex;
  justify-content: space-between;
}
.about-key {
  font-size: 12px;
  color: var(--tt-on-surface-variant);
}
.about-value {
  font-size: 12px;
  color: var(--tt-on-surface);
}

.error {
  font-size: 12px;
  color: var(--tt-error);
}
</style>
