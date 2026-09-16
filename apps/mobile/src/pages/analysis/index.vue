<template>
  <view class="tt-page">
    <!-- 骨架屏 -->
    <view v-if="loading" class="skeleton-wrap">
      <view class="tt-skeleton skeleton-hero" />
      <view v-for="i in 3" :key="i" class="tt-skeleton skeleton-card" />
    </view>

    <view v-else-if="!analysis" class="tt-empty">
      这次考试还没有分析结果
      <view class="empty-action" hover-class="tt-hover-light" @tap="goImport">去导入答题卡并分析</view>
    </view>

    <template v-else>
      <!-- 总览：始终可见，作为「先给一点」的锚点 -->
      <view class="hero tt-anim-fade-up">
        <text class="hero__label">{{ examName || '本次考试' }}</text>
        <view class="hero__value">
          <text class="hero__number">{{ percent(analysis.score_rate) }}</text>
          <text class="hero__level">{{ levelText }}</text>
        </view>
        <view class="hero__meta">
          <text class="hero__score">{{ analysis.total_score }} / {{ analysis.full_score }} 分</text>
          <text class="hero__hint">共 3 段 · 看视频逐段解锁</text>
        </view>
        <view class="hero__glow" />
      </view>

      <!-- 三个阶段 -->
      <view
        v-for="(item, idx) in stageList"
        :key="item.stage"
        class="stage tt-anim-fade-up"
        :class="{ 'stage--open': item.unlocked, 'stage--just': justUnlocked.indexOf(item.stage) > -1 }"
        :style="{ animationDelay: 0.06 * (idx + 1) + 's' }"
      >
        <!-- 阶段头 -->
        <view class="stage__head">
          <view class="stage__badge" :class="{ 'stage__badge--open': item.unlocked }">
            <text>{{ item.unlocked ? '✓' : item.stage }}</text>
          </view>
          <view class="tt-flex1">
            <view class="stage__title-row">
              <text class="stage__title">{{ item.name }}</text>
              <view v-if="item.unlocked" class="tt-tag tt-tag--success">已解锁</view>
              <view v-else class="tt-tag tt-tag--ad">看 {{ remaining(item) }} 次视频</view>
            </view>
            <text class="stage__desc">{{ stageDesc(item.stage) }}</text>
          </view>
        </view>

        <!-- 未解锁 -->
        <view v-if="!item.unlocked" class="lock-box">
          <view class="lock">
            <view class="lock__shackle" />
            <view class="lock__body"><view class="lock__keyhole" /></view>
          </view>

          <view class="progress">
            <view class="progress__track">
              <view class="progress__bar" :style="{ width: progressPercent(item) + '%' }">
                <view class="progress__glow" />
              </view>
            </view>
            <text class="progress__text">{{ item.watched }} / {{ item.ad_required }} 次</text>
          </view>

          <view
            class="tt-btn tt-btn--ad unlock-btn"
            :class="{ 'tt-btn--disabled': busyStage === item.stage }"
            hover-class="tt-hover"
            @tap="unlockByAd(item)"
          >
            {{ busyStage === item.stage ? '处理中…' : `看视频解锁（${remaining(item)} 次）` }}
          </view>
          <view class="lock-box__alt" hover-class="tt-hover-light" @tap="unlockByCredit(item)">
            或用 {{ creditCost }} 点数直接解锁
          </view>
        </view>

        <!-- 已解锁 -->
        <view v-else class="stage__body tt-anim-fade-in">
          <!-- ① 基础分析 -->
          <template v-if="item.stage === 1 && s1">
            <text class="summary-text">{{ s1.summary }}</text>
            <view class="mini-stats">
              <view class="mini-stat">
                <text class="mini-stat__num">{{ percent(s1.score_rate) }}</text>
                <text class="mini-stat__label">总分率</text>
              </view>
              <view class="mini-stat">
                <text class="mini-stat__num">{{ s1.total_score }}</text>
                <text class="mini-stat__label">总分</text>
              </view>
              <view class="mini-stat">
                <text class="mini-stat__num">{{ s1.level_text }}</text>
                <text class="mini-stat__label">水平</text>
              </view>
            </view>
            <view v-if="s1.top_subjects.length" class="strong-list">
              <text class="block-label">相对较好的学科</text>
              <view v-for="sub in s1.top_subjects" :key="sub.name" class="strong-item">
                <text class="strong-item__name">{{ sub.name }}</text>
                <view class="strong-item__track">
                  <view class="strong-item__bar" :style="{ width: Math.min(100, sub.rate * 100) + '%' }" />
                </view>
                <text class="strong-item__rate">{{ percent(sub.rate) }}</text>
              </view>
            </view>
          </template>

          <!-- ② 进阶分析 -->
          <template v-else-if="item.stage === 2 && s2">
            <view class="lost-total">
              <text class="lost-total__num">{{ s2.total_lost }}</text>
              <text class="lost-total__label">分 · 集中在这 {{ s2.weak_questions.length }} 道题</text>
            </view>
            <view
              v-for="(q, qi) in s2.weak_questions"
              :key="q.question_no"
              class="wq tt-anim-fade-up"
              :style="{ animationDelay: 0.04 * qi + 's' }"
            >
              <view class="wq__head">
                <text class="wq__no">{{ q.question_no }}</text>
                <text class="wq__subject">{{ q.subject_name }}</text>
                <text class="wq__lost">-{{ q.lost_score }}</text>
              </view>
              <text class="wq__point">{{ q.knowledge_point }}</text>
              <text class="wq__brief">{{ q.brief }}</text>
              <view class="wq__tags">
                <text :class="['tt-tag', severityClass(q.severity)]">{{ q.error_type }}</text>
                <text class="tt-tag">{{ q.module }}</text>
              </view>
            </view>
          </template>

          <!-- ③ 归纳总结 -->
          <template v-else-if="item.stage === 3 && s3">
            <text class="block-label">需要重点补的知识模块</text>
            <view
              v-for="(m, mi) in s3.modules"
              :key="m.module + mi"
              class="module tt-anim-slide-right"
              :style="{ animationDelay: 0.05 * mi + 's' }"
            >
              <view class="module__rank" :class="'module__rank--p' + m.priority">{{ m.priority }}</view>
              <view class="tt-flex1">
                <view class="module__title-row">
                  <text class="module__name">{{ m.module }}</text>
                  <text class="module__subject">{{ m.subject_name }}</text>
                  <text class="module__lost">-{{ m.lost_score_total }}分</text>
                </view>
                <text class="module__reason">{{ m.reason }}</text>
              </view>
            </view>

            <text class="block-label block-label--gap">学习建议</text>
            <view v-for="(s, si) in s3.suggestions" :key="si" class="sugg">
              <text class="sugg__idx">{{ si + 1 }}</text>
              <text class="sugg__text">{{ s }}</text>
            </view>

            <template v-if="s3.improvement_plan">
              <text class="block-label block-label--gap">四周提升计划</text>
              <view v-for="w in s3.improvement_plan.weekly_plan" :key="w.week" class="week">
                <view class="week__badge">第 {{ w.week }} 周</view>
                <view class="tt-flex1">
                  <text class="week__focus">{{ w.focus }}</text>
                  <text class="week__target">目标：{{ w.target }}</text>
                </view>
              </view>
            </template>
          </template>

          <view v-else class="tt-empty">内容加载中，请稍后重试</view>
        </view>
      </view>

      <view class="tt-btn tt-btn--plain bottom-btn" hover-class="tt-hover" @tap="goImport">
        继续导入下一次考试
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { fetchAnalyses } from '@/api'
import {
  fetchStagedAnalysis,
  newAdEventId,
  remainingAdCount,
  reportStageAd,
  unlockStageByCredit
} from '@/api/stages'
import { playRewardedAd } from '@/utils/ad'
import { useUserStore } from '@/store/user'
import { percent } from '@/utils/format'
import { asPlan, asQuestions, asSummary, STAGE_META } from '@/types/stage'
import type { AnalysisStage, StageNo, StagedAnalysis, WeakQuestion } from '@/types/stage'

/** 用点数跳过广告的价格，与后端 stage_unlock_credit_cost 保持一致 */
const creditCost = 1

const userStore = useUserStore()

const analysisId = ref('')
const analysis = ref<StagedAnalysis | null>(null)
const examName = ref('')
const loading = ref(true)
/** 正在解锁的阶段（0 表示空闲），用于按钮态与防重复点击 */
const busyStage = ref<StageNo | 0>(0)
/** 刚解锁的阶段，用于播放一次高亮动画 */
const justUnlocked = ref<StageNo[]>([])

const stageList = computed<AnalysisStage[]>(() => analysis.value?.stages ?? [])

const levelText = computed(() => {
  const rate = analysis.value?.score_rate ?? 0
  if (rate >= 0.85) return '优秀'
  if (rate >= 0.7) return '良好'
  if (rate >= 0.6) return '及格'
  return '待提升'
})

/** 三个阶段的内容，按 stage 收窄类型 */
const s1 = computed(() => asSummary(stageList.value.find((item) => item.stage === 1)?.content ?? null))
const s2 = computed(() => asQuestions(stageList.value.find((item) => item.stage === 2)?.content ?? null))
const s3 = computed(() => asPlan(stageList.value.find((item) => item.stage === 3)?.content ?? null))

function stageDesc(stage: StageNo): string {
  const meta = STAGE_META.find((item) => item.stage === stage)
  return meta ? meta.desc : ''
}

function remaining(stage: AnalysisStage): number {
  return remainingAdCount(stage)
}

function progressPercent(stage: AnalysisStage): number {
  if (!stage.ad_required) return 0
  return Math.min(100, Math.round((stage.watched / stage.ad_required) * 100))
}

function severityClass(severity: WeakQuestion['severity']): string {
  if (severity === 'high') return 'tt-tag--danger'
  if (severity === 'medium') return 'tt-tag--warn'
  return 'tt-tag--success'
}

/** 把服务端返回的最新阶段状态合并回本地，避免整页重新拉取 */
function applyStageResult(result: {
  stage: StageNo
  watched: number
  unlocked: boolean
  unlock_method: 'ad' | 'credit' | null
  content: unknown
}): void {
  const current = analysis.value
  if (!current) return
  const list = current.stages.map((item) =>
    item.stage === result.stage
      ? {
          ...item,
          watched: result.watched,
          unlocked: result.unlocked,
          unlock_method: result.unlock_method,
          content: (result.content as AnalysisStage['content']) ?? null
        }
      : item
  )
  analysis.value = { ...current, stages: list }
}

function markJustUnlocked(stage: StageNo): void {
  justUnlocked.value = justUnlocked.value.concat([stage])
  setTimeout(() => {
    justUnlocked.value = justUnlocked.value.filter((item) => item !== stage)
  }, 1400)
}

/**
 * 看视频解锁。
 * 差几次就看几次，每看完一次立刻上报给服务端——这样用户中途退出也不会丢失已累计的进度。
 */
async function unlockByAd(stage: AnalysisStage): Promise<void> {
  if (busyStage.value) return
  const need = remaining(stage)
  if (need <= 0) return

  busyStage.value = stage.stage
  try {
    for (let i = 0; i < need; i++) {
      const played = await playRewardedAd()
      if (!played.ok) {
        if (played.reason) uni.showToast({ title: played.reason, icon: 'none' })
        return
      }
      const result = await reportStageAd(
        analysisId.value,
        stage.stage,
        newAdEventId(analysisId.value, stage.stage)
      )
      applyStageResult(result)
      if (result.unlocked) {
        markJustUnlocked(stage.stage)
        uni.showToast({ title: stage.name + '已解锁', icon: 'none' })
        break
      }
    }
  } catch (e: any) {
    uni.showToast({ title: (e && e.message) || '解锁失败，请重试', icon: 'none' })
  } finally {
    busyStage.value = 0
  }
}

/** 用点数跳过广告 */
async function unlockByCredit(stage: AnalysisStage): Promise<void> {
  if (busyStage.value) return
  if (userStore.credits < creditCost) {
    uni.showModal({
      title: '点数不足',
      content: `用点数直接解锁需要 ${creditCost} 点数，当前余额 ${userStore.credits} 点。也可以选择看视频免费解锁。`,
      showCancel: false,
      confirmText: '知道了'
    })
    return
  }
  busyStage.value = stage.stage
  try {
    const result = await unlockStageByCredit(analysisId.value, stage.stage)
    applyStageResult(result)
    markJustUnlocked(stage.stage)
    uni.showToast({ title: stage.name + '已解锁', icon: 'none' })
  } catch (e: any) {
    uni.showToast({ title: (e && e.message) || '解锁失败，请重试', icon: 'none' })
  } finally {
    busyStage.value = 0
  }
}

function goImport(): void {
  uni.switchTab({ url: '/pages/import/index' })
}

onLoad(async (query) => {
  const params = (query || {}) as Record<string, string>
  if (params.name) {
    try {
      examName.value = decodeURIComponent(params.name)
    } catch (e) {
      examName.value = params.name
    }
  }
  analysisId.value = params.analysis_id || ''

  try {
    // 只给了考试 ID 时，先取该考试最新的一条分析
    if (!analysisId.value && params.exam_id) {
      const list = await fetchAnalyses(params.exam_id, 1, 1)
      analysisId.value = (list.items[0] && list.items[0].id) || ''
    }
    if (!analysisId.value) {
      loading.value = false
      return
    }
    analysis.value = await fetchStagedAnalysis(analysisId.value)
  } catch (e: any) {
    uni.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
})
</script>

<style lang="scss" scoped>
/* ------------------------------------------------------------------ 骨架 */

.skeleton-wrap {
  padding-top: 8rpx;
}

.skeleton-hero {
  height: 280rpx;
  margin-bottom: 24rpx;
  border-radius: $tt-radius-lg;
}

.skeleton-card {
  height: 220rpx;
  margin-bottom: 24rpx;
  border-radius: $tt-radius;
}

/* ------------------------------------------------------------------ 总览 */

.hero {
  position: relative;
  overflow: hidden;
  background: $tt-gradient;
  border-radius: $tt-radius-lg;
  padding: 40rpx 32rpx 32rpx;
  margin-bottom: 28rpx;
  box-shadow: $tt-shadow-strong;
}

.hero__label {
  display: block;
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.82);
}

.hero__value {
  display: flex;
  align-items: baseline;
  margin: 12rpx 0 16rpx;
}

.hero__number {
  font-size: 80rpx;
  font-weight: 700;
  color: #ffffff;
  letter-spacing: -2rpx;
  line-height: 1.1;
}

.hero__level {
  margin-left: 20rpx;
  font-size: 26rpx;
  font-weight: 600;
  color: $tt-primary-dark;
  background-color: rgba(255, 255, 255, 0.92);
  padding: 6rpx 22rpx;
  border-radius: 24rpx;
}

.hero__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.hero__score {
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.85);
}

.hero__hint {
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.7);
}

.hero__glow {
  position: absolute;
  top: -80rpx;
  right: -60rpx;
  width: 260rpx;
  height: 260rpx;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.28) 0%, rgba(255, 255, 255, 0) 70%);
}

/* ------------------------------------------------------------------ 阶段 */

.stage {
  background-color: $tt-card;
  border-radius: $tt-radius;
  padding: 28rpx;
  margin-bottom: 24rpx;
  box-shadow: $tt-shadow;
  border: 2rpx solid transparent;
  transition: border-color 0.3s ease;
}

.stage--open {
  border-color: rgba(124, 92, 255, 0.16);
}

.stage--just {
  border-color: $tt-primary;
}

.stage__head {
  display: flex;
  align-items: flex-start;
}

.stage__badge {
  width: 64rpx;
  height: 64rpx;
  border-radius: 20rpx;
  background-color: #f2f0f9;
  color: $tt-text-weak;
  font-size: 30rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 20rpx;
  flex-shrink: 0;
  transition: background 0.3s ease, color 0.3s ease, transform 0.3s ease;
}

.stage__badge--open {
  background: $tt-gradient;
  color: #ffffff;
  transform: scale(1.04);
}

.stage__title-row {
  display: flex;
  align-items: center;
}

.stage__title {
  font-size: 32rpx;
  font-weight: 600;
  color: $tt-text;
  margin-right: 16rpx;
}

.stage__desc {
  display: block;
  font-size: 24rpx;
  color: $tt-text-weak;
  margin-top: 8rpx;
}

/* ------------------------------------------------------------------ 锁 */

.lock-box {
  padding: 32rpx 0 8rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.lock {
  width: 88rpx;
  height: 88rpx;
  position: relative;
  margin-bottom: 24rpx;
}

.lock__shackle {
  position: absolute;
  top: 0;
  left: 50%;
  margin-left: -26rpx;
  width: 52rpx;
  height: 46rpx;
  border: 8rpx solid #cfc9e6;
  border-bottom: none;
  border-radius: 26rpx 26rpx 0 0;
}

.lock__body {
  position: absolute;
  bottom: 0;
  left: 50%;
  margin-left: -36rpx;
  width: 72rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: linear-gradient(180deg, #ddd8f0 0%, #cfc9e6 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.lock__keyhole {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background-color: #ffffff;
  position: relative;
}

.lock__keyhole::after {
  content: '';
  position: absolute;
  top: 10rpx;
  left: 3rpx;
  width: 6rpx;
  height: 14rpx;
  border-radius: 3rpx;
  background-color: #ffffff;
}

/* ------------------------------------------------------------------ 进度 */

.progress {
  width: 100%;
  display: flex;
  align-items: center;
  margin-bottom: 28rpx;
}

.progress__track {
  flex: 1;
  height: 14rpx;
  background-color: #f0eef8;
  border-radius: 7rpx;
  overflow: hidden;
}

.progress__bar {
  position: relative;
  height: 100%;
  border-radius: 7rpx;
  background: $tt-gradient-accent;
  transition: width 0.45s cubic-bezier(0.22, 0.68, 0, 1);
  overflow: hidden;
}

.progress__glow {
  position: absolute;
  top: 0;
  left: 0;
  width: 60%;
  height: 100%;
  background: linear-gradient(90deg, rgba(255, 255, 255, 0) 0%, rgba(255, 255, 255, 0.55) 50%, rgba(255, 255, 255, 0) 100%);
  animation: tt-glow 1.8s ease-in-out infinite;
}

.progress__text {
  margin-left: 20rpx;
  font-size: 24rpx;
  color: $tt-text-sub;
}

.unlock-btn {
  width: 100%;
}

.lock-box__alt {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: $tt-text-weak;
  padding: 10rpx 20rpx;
}

/* ------------------------------------------------------------------ 内容 */

.stage__body {
  margin-top: 24rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid $tt-border;
}

.block-label {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: $tt-text;
  margin-bottom: 16rpx;
}

.block-label--gap {
  margin-top: 32rpx;
}

.summary-text {
  display: block;
  font-size: 27rpx;
  line-height: 1.75;
  color: $tt-text;
  background-color: $tt-primary-light;
  border-radius: $tt-radius-sm;
  padding: 24rpx;
}

.mini-stats {
  display: flex;
  margin: 24rpx 0 8rpx;
}

.mini-stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.mini-stat__num {
  font-size: 34rpx;
  font-weight: 700;
  color: $tt-primary;
}

.mini-stat__label {
  font-size: 22rpx;
  color: $tt-text-weak;
  margin-top: 6rpx;
}

.strong-list {
  margin-top: 24rpx;
}

.strong-item {
  display: flex;
  align-items: center;
  margin-bottom: 14rpx;
}

.strong-item__name {
  width: 96rpx;
  font-size: 25rpx;
  color: $tt-text-sub;
}

.strong-item__track {
  flex: 1;
  height: 12rpx;
  background-color: #f0eef8;
  border-radius: 6rpx;
  overflow: hidden;
  margin: 0 16rpx;
}

.strong-item__bar {
  height: 100%;
  border-radius: 6rpx;
  background: $tt-gradient;
}

.strong-item__rate {
  width: 90rpx;
  text-align: right;
  font-size: 24rpx;
  font-weight: 600;
  color: $tt-text;
}

.lost-total {
  display: flex;
  align-items: baseline;
  margin-bottom: 20rpx;
}

.lost-total__num {
  font-size: 46rpx;
  font-weight: 700;
  color: $tt-danger;
  margin-right: 10rpx;
}

.lost-total__label {
  font-size: 24rpx;
  color: $tt-text-sub;
}

.wq {
  border-left: 6rpx solid rgba(124, 92, 255, 0.28);
  background-color: #faf9ff;
  border-radius: 0 $tt-radius-sm $tt-radius-sm 0;
  padding: 20rpx 22rpx;
  margin-bottom: 16rpx;
}

.wq__head {
  display: flex;
  align-items: center;
}

.wq__no {
  font-size: 26rpx;
  font-weight: 700;
  color: $tt-primary;
  margin-right: 14rpx;
}

.wq__subject {
  flex: 1;
  font-size: 23rpx;
  color: $tt-text-weak;
}

.wq__lost {
  font-size: 28rpx;
  font-weight: 700;
  color: $tt-danger;
}

.wq__point {
  display: block;
  font-size: 26rpx;
  color: $tt-text;
  line-height: 1.55;
  margin: 10rpx 0 8rpx;
}

.wq__brief {
  display: block;
  font-size: 24rpx;
  color: $tt-text-sub;
  line-height: 1.6;
}

.wq__tags {
  display: flex;
  flex-wrap: wrap;
  margin-top: 14rpx;
}

.wq__tags .tt-tag {
  margin-right: 12rpx;
  margin-bottom: 8rpx;
}

.module {
  display: flex;
  align-items: flex-start;
  padding: 20rpx 0;
  border-bottom: 1rpx solid $tt-border;
}

.module:last-of-type {
  border-bottom: none;
}

.module__rank {
  width: 44rpx;
  height: 44rpx;
  border-radius: 14rpx;
  font-size: 24rpx;
  font-weight: 700;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 18rpx;
  flex-shrink: 0;
}

.module__rank--p1 {
  background: linear-gradient(135deg, #f5544f, #d93b36);
}

.module__rank--p2 {
  background: $tt-gradient;
}

.module__rank--p3 {
  background: linear-gradient(135deg, #b9b3cf, #9e9bb0);
}

.module__title-row {
  display: flex;
  align-items: center;
}

.module__name {
  font-size: 27rpx;
  font-weight: 600;
  color: $tt-text;
  margin-right: 14rpx;
}

.module__subject {
  flex: 1;
  font-size: 22rpx;
  color: $tt-text-weak;
}

.module__lost {
  font-size: 24rpx;
  font-weight: 700;
  color: $tt-danger;
}

.module__reason {
  display: block;
  font-size: 24rpx;
  color: $tt-text-sub;
  line-height: 1.6;
  margin-top: 8rpx;
}

.sugg {
  display: flex;
  align-items: flex-start;
  margin-bottom: 16rpx;
}

.sugg__idx {
  width: 36rpx;
  height: 36rpx;
  border-radius: 18rpx;
  background-color: $tt-primary-light;
  color: $tt-primary;
  font-size: 22rpx;
  font-weight: 600;
  text-align: center;
  line-height: 36rpx;
  margin-right: 16rpx;
  flex-shrink: 0;
}

.sugg__text {
  flex: 1;
  font-size: 25rpx;
  color: $tt-text-sub;
  line-height: 1.65;
}

.week {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid $tt-border;
}

.week:last-child {
  border-bottom: none;
}

.week__badge {
  width: 104rpx;
  height: 48rpx;
  line-height: 48rpx;
  text-align: center;
  border-radius: 24rpx;
  background-color: $tt-primary-light;
  color: $tt-primary;
  font-size: 22rpx;
  margin-right: 20rpx;
  flex-shrink: 0;
}

.week__focus {
  display: block;
  font-size: 26rpx;
  color: $tt-text;
}

.week__target {
  display: block;
  font-size: 22rpx;
  color: $tt-text-weak;
  margin-top: 6rpx;
}

.bottom-btn {
  margin: 8rpx 0 40rpx;
}

.empty-action {
  margin-top: 24rpx;
  color: $tt-primary;
  font-size: 26rpx;
}
</style>
