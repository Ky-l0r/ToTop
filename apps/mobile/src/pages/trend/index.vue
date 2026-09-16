<template>
  <view class="tt-page">
    <!-- 成绩概览 -->
    <view class="overview tt-anim-fade-up">
      <view class="overview__head">
        <text class="overview__label">最近一次总分得分率</text>
        <view v-if="isDemo" class="tt-demo-badge">演示数据</view>
      </view>

      <view class="overview__value">
        <text class="overview__number">{{ latest ? percent(latest.total_rate) : '--' }}</text>
        <text :class="['overview__delta', delta >= 0 ? 'is-up' : 'is-down']">
          {{ latest ? deltaPt(delta) : '' }}
        </text>
      </view>

      <view class="overview__meta">
        <text class="overview__exam">{{ latest ? latest.exam_name : '还没有考试记录' }}</text>
        <text class="tt-weak">{{ latest ? dateOnly(latest.exam_date) : '' }}</text>
      </view>

      <view class="overview__stats">
        <view class="stat">
          <text class="stat__num" :style="{ color: strongest ? subjectColor(strongest.code) : '#9E9BB0' }">
            {{ strongest ? strongest.name : '--' }}
          </text>
          <text class="stat__label">最强学科</text>
        </view>
        <view class="stat">
          <text class="stat__num" :style="{ color: weakest ? subjectColor(weakest.code) : '#9E9BB0' }">
            {{ weakest ? weakest.name : '--' }}
          </text>
          <text class="stat__label">最弱学科</text>
        </view>
        <view class="stat">
          <text class="stat__num">{{ trendPoints.length }}</text>
          <text class="stat__label">考试次数</text>
        </view>
      </view>
    </view>

    <!-- 学科筛选 -->
    <scroll-view class="chips tt-anim-fade-up tt-delay-1" scroll-x :show-scrollbar="false">
      <view class="chips__inner">
        <view
          class="chip"
          :class="{ 'chip--on': selectedSubjects.length === 0 }"
          hover-class="tt-hover"
          @tap="selectAll"
        >
          全部学科
        </view>
        <view
          v-for="subject in subjects"
          :key="subject.code"
          class="chip"
          :class="{ 'chip--on': selectedSubjects.indexOf(subject.code) > -1 }"
          hover-class="tt-hover"
          @tap="toggleSubject(subject.code)"
        >
          <view class="chip__dot" :style="{ background: subjectColor(subject.code) }" />
          <text>{{ subject.name }}</text>
        </view>
      </view>
    </scroll-view>

    <!-- 趋势折线图 -->
    <view class="tt-card tt-anim-fade-up tt-delay-2">
      <view class="tt-card__title">
        <text>分数变化趋势</text>
        <text class="tt-card__subtitle">纵轴为得分率，点击数据点看详情</text>
      </view>
      <LineChart
        :width="chartWidth"
        :height="240"
        :labels="chartLabels"
        :series="chartSeries"
        @point-click="handlePointClick"
      />
    </view>

    <!-- 历次考试 -->
    <view class="tt-card tt-anim-fade-up tt-delay-3">
      <view class="tt-card__title">
        <text>历次考试</text>
        <text class="tt-card__subtitle">共 {{ examList.length }} 次</text>
      </view>

      <view v-if="!examList.length" class="tt-empty">还没有考试记录，去「导入」页添加吧</view>

      <view
        v-for="(exam, index) in examList"
        :key="exam.id"
        class="exam-item"
        hover-class="tt-hover"
        @tap="openExam(exam)"
      >
        <view class="exam-item__left">
          <text class="exam-item__name">{{ exam.name }}</text>
          <view class="exam-item__meta">
            <text class="tt-weak">{{ dateOnly(exam.exam_date) }}</text>
            <text class="tt-weak">·</text>
            <text class="tt-weak">{{ exam.subject_count }} 科</text>
            <view :class="['tt-tag', statusClass(exam.analysis_status)]">{{ statusText(exam.analysis_status) }}</view>
          </view>
        </view>
        <view class="exam-item__right">
          <text class="exam-item__rate">{{ rateOf(exam.id) }}</text>
          <text
            v-if="indexOfPoint(exam.id) > 0"
            :class="['exam-item__delta', examDelta(exam.id) >= 0 ? 'is-up' : 'is-down']"
          >
            {{ deltaPt(examDelta(exam.id)) }}
          </text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import LineChart from '@/components/LineChart.vue'
import { fetchExams, fetchTrends } from '@/api'
import type { ChartSeries } from '@/types/chart'
import { USE_MOCK } from '@/api/config'
import { dateOnly, deltaPt, percent, subjectColor } from '@/utils/format'
import type { ExamSummary, TrendData } from '@/types'

const isDemo = USE_MOCK
const trend = ref<TrendData>({ points: [], subjects: [] })
const examList = ref<ExamSummary[]>([])
const selectedSubjects = ref<string[]>([])
const chartWidth = ref(300)

const trendPoints = computed(() => trend.value.points)
const subjects = computed(() => trend.value.subjects)
const latest = computed(() => {
  const points = trendPoints.value
  return points.length ? points[points.length - 1] : null
})
const previous = computed(() => {
  const points = trendPoints.value
  return points.length > 1 ? points[points.length - 2] : null
})
const delta = computed(() => {
  if (!latest.value || !previous.value) return 0
  return (latest.value.total_rate - previous.value.total_rate) * 100
})

/** 最近一次考试中各科的强弱 */
const strongest = computed(() => pickSubject(true))
const weakest = computed(() => pickSubject(false))

function pickSubject(best: boolean): { code: string; name: string } | null {
  if (!latest.value) return null
  let result: { code: string; name: string } | null = null
  let edge = best ? -1 : 2
  subjects.value.forEach((subject) => {
    const rate = latest.value!.subjects[subject.code]
    if (rate === undefined) return
    if (best ? rate > edge : rate < edge) {
      edge = rate
      result = { code: subject.code, name: subject.name }
    }
  })
  return result
}

const chartLabels = computed(() => trendPoints.value.map((p) => p.exam_name.replace(/^.*·/, '')))

/** 总分线 + 选中的学科线 */
const chartSeries = computed<ChartSeries[]>(() => {
  const points = trendPoints.value
  if (!points.length) return []

  const list: ChartSeries[] = [
    {
      name: '总分',
      color: subjectColor('total'),
      lineWidth: 3,
      data: points.map((p) => p.total_rate)
    }
  ]

  const codes = selectedSubjects.value.length
    ? selectedSubjects.value
    : subjects.value.map((s) => s.code)

  codes.forEach((code) => {
    const meta = subjects.value.find((s) => s.code === code)
    if (!meta) return
    list.push({
      name: meta.name,
      color: subjectColor(code),
      lineWidth: 2,
      data: points.map((p) => (p.subjects[code] === undefined ? null : p.subjects[code]))
    })
  })

  return list
})

function toggleSubject(code: string): void {
  const list = selectedSubjects.value.slice()
  const index = list.indexOf(code)
  if (index > -1) list.splice(index, 1)
  else list.push(code)
  selectedSubjects.value = list
}

function selectAll(): void {
  selectedSubjects.value = []
}

function indexOfPoint(examId: string): number {
  return trendPoints.value.findIndex((p) => p.exam_id === examId)
}

function rateOf(examId: string): string {
  const point = trendPoints.value.find((p) => p.exam_id === examId)
  return point ? percent(point.total_rate) : '--'
}

function examDelta(examId: string): number {
  const index = indexOfPoint(examId)
  if (index <= 0) return 0
  return (trendPoints.value[index].total_rate - trendPoints.value[index - 1].total_rate) * 100
}

function statusText(status: ExamSummary['analysis_status']): string {
  if (status === 'succeeded') return '已分析'
  if (status === 'running' || status === 'pending') return '分析中'
  if (status === 'failed') return '分析失败'
  return '未分析'
}

function statusClass(status: ExamSummary['analysis_status']): string {
  if (status === 'succeeded') return 'tt-tag--success'
  if (status === 'running' || status === 'pending') return 'tt-tag--warn'
  if (status === 'failed') return 'tt-tag--danger'
  return ''
}

function openExam(exam: ExamSummary): void {
  uni.navigateTo({ url: `/pages/analysis/index?exam_id=${exam.id}&name=${encodeURIComponent(exam.name)}` })
}

function handlePointClick(index: number): void {
  const point = trendPoints.value[index]
  if (!point) return
  const lines = subjects.value
    .map((s) => ({ name: s.name, rate: point.subjects[s.code] }))
    .filter((item) => item.rate !== undefined)
    .sort((a, b) => (a.rate as number) - (b.rate as number))
    .slice(0, 3)
    .map((item) => `${item.name} ${percent(item.rate as number)}`)
    .join('　')
  uni.showModal({
    title: point.exam_name,
    content: `总分得分率 ${percent(point.total_rate)}\n最弱三科：${lines}`,
    confirmText: '查看分析',
    cancelText: '关闭',
    success: (res) => {
      if (res.confirm) openExam({ id: point.exam_id, name: point.exam_name } as ExamSummary)
    }
  })
}

function measureChart(): void {
  const info = uni.getSystemInfoSync()
  const rpx2px = info.windowWidth / 750
  // 页面左右 padding 24rpx ×2，卡片内 padding 28rpx ×2
  chartWidth.value = Math.max(240, info.windowWidth - (24 * 2 + 28 * 2) * rpx2px)
}

async function loadData(): Promise<void> {
  const [trendData, exams] = await Promise.all([fetchTrends(10), fetchExams(1, 30)])
  trend.value = trendData
  examList.value = exams.items
}

onLoad(() => {
  measureChart()
  loadData().catch((e) => {
    console.error('[trend] 加载失败', e)
    uni.showToast({ title: '数据加载失败', icon: 'none' })
  })
})

onShow(() => {
  // 从导入页分析完回来时刷新
  if (trend.value.points.length) loadData().catch(() => {})
})

onPullDownRefresh(async () => {
  try {
    await loadData()
    uni.showToast({ title: '已刷新', icon: 'none' })
  } finally {
    uni.stopPullDownRefresh()
  }
})
</script>

<style lang="scss" scoped>
.overview {
  background: linear-gradient(135deg, #8b6cff 0%, #6344e8 100%);
  border-radius: $tt-radius-lg;
  padding: 36rpx 32rpx 28rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 10rpx 30rpx rgba(76, 111, 255, 0.25);
}

.overview__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.overview__label {
  color: rgba(255, 255, 255, 0.8);
  font-size: 24rpx;
}

.overview__value {
  display: flex;
  align-items: baseline;
  margin: 16rpx 0 8rpx;
}

.overview__number {
  color: #ffffff;
  font-size: 76rpx;
  font-weight: 700;
  letter-spacing: -1rpx;
}

.overview__delta {
  margin-left: 20rpx;
  font-size: 26rpx;
  font-weight: 600;
  padding: 4rpx 16rpx;
  border-radius: 20rpx;
}

.overview__delta.is-up {
  color: #d9ffe6;
  background-color: rgba(34, 197, 94, 0.28);
}

.overview__delta.is-down {
  color: #ffe0e0;
  background-color: rgba(239, 68, 68, 0.3);
}

.overview__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.overview__exam {
  color: #ffffff;
  font-size: 26rpx;
}

.overview__stats {
  display: flex;
  margin-top: 28rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.2);
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat__num {
  color: #ffffff;
  font-size: 30rpx;
  font-weight: 600;
}

.stat__label {
  color: rgba(255, 255, 255, 0.7);
  font-size: 22rpx;
  margin-top: 6rpx;
}

.chips {
  white-space: nowrap;
  margin-bottom: 24rpx;
}

.chips__inner {
  display: inline-flex;
  align-items: center;
  padding: 2rpx 0;
}

.chip {
  display: inline-flex;
  align-items: center;
  height: 60rpx;
  padding: 0 24rpx;
  margin-right: 16rpx;
  border-radius: 30rpx;
  background-color: #ffffff;
  color: $tt-text-sub;
  font-size: 24rpx;
  border: 1rpx solid $tt-border;
}

.chip--on {
  background-color: $tt-primary-light;
  color: $tt-primary;
  border-color: rgba(76, 111, 255, 0.35);
  font-weight: 600;
}

.chip__dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 7rpx;
  margin-right: 10rpx;
}

.exam-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 22rpx 0;
  border-bottom: 1rpx solid $tt-border;

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }
}

.exam-item__left {
  flex: 1;
  min-width: 0;
}

.exam-item__name {
  font-size: 28rpx;
  color: $tt-text;
  font-weight: 500;
}

.exam-item__meta {
  display: flex;
  align-items: center;
  margin-top: 10rpx;
}

.exam-item__meta .tt-weak {
  margin-right: 10rpx;
}

.exam-item__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  margin-left: 20rpx;
}

.exam-item__rate {
  font-size: 32rpx;
  font-weight: 700;
  color: $tt-text;
}

.exam-item__delta {
  font-size: 22rpx;
  margin-top: 4rpx;
}

.exam-item__delta.is-up {
  color: $tt-success;
}

.exam-item__delta.is-down {
  color: $tt-danger;
}
</style>
