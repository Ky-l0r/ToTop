<template>
  <view class="tt-page">
    <!-- 当前考试 -->
    <view class="tt-card">
      <view class="exam-bar" @tap="chooseExam">
        <view class="tt-flex1">
          <text class="exam-bar__label">当前考试</text>
          <text :class="['exam-bar__name', currentExam ? '' : 'is-empty']">
            {{ currentExam ? currentExam.name : '点击选择或新建一次考试' }}
          </text>
          <text v-if="currentExam" class="tt-weak exam-bar__date">
            {{ dateOnly(currentExam.exam_date) }} · {{ currentExam.grade }}
          </text>
        </view>
        <text class="exam-bar__action">{{ currentExam ? '切换' : '新建' }}</text>
      </view>
    </view>

    <!-- 题目导入 -->
    <view class="tt-card">
      <view class="tt-card__title">
        <text>① 导入题目（试卷）</text>
        <text class="tt-card__subtitle">{{ papers.length }} 份</text>
      </view>
      <view class="hint">AI 需要知道「考了哪些题、每题考什么」，才能把失分定位到知识点。支持拍照、PDF、Word、图片。</view>

      <view class="source-row">
        <view class="source-btn" @tap="pickAssets('paper', 'camera')">
          <text class="source-btn__icon">◉</text>
          <text class="source-btn__text">拍照</text>
        </view>
        <view class="source-btn" @tap="pickAssets('paper', 'album')">
          <text class="source-btn__icon">▣</text>
          <text class="source-btn__text">相册</text>
        </view>
        <view class="source-btn" @tap="pickAssets('paper', 'file')">
          <text class="source-btn__icon">▤</text>
          <text class="source-btn__text">PDF / Word</text>
        </view>
      </view>

      <view v-if="papers.length" class="asset-list">
        <view v-for="item in papers" :key="item.key" class="asset">
          <image
            v-if="isImage(item)"
            class="asset__thumb"
            :src="item.path"
            mode="aspectFill"
          />
          <view v-else class="asset__thumb asset__thumb--file">
            <text>{{ item.sourceType === 'pdf' ? 'PDF' : 'DOC' }}</text>
          </view>
          <view class="tt-flex1">
            <text class="asset__name">{{ item.name }}</text>
            <text class="tt-weak">{{ item.sizeText }} · {{ sourceLabel(item.sourceType) }}</text>
          </view>
          <text class="asset__del" @tap="removeAsset('paper', item.key)">删除</text>
        </view>
      </view>
    </view>

    <!-- 答题卡导入 -->
    <view class="tt-card">
      <view class="tt-card__title">
        <text>② 导入答题卡</text>
        <text class="tt-card__subtitle">{{ sheets.length }} 份</text>
      </view>
      <view class="hint">答题卡上有你的实际作答与老师批改痕迹，是判断失分原因的关键依据。</view>

      <view class="segment">
        <view
          class="segment__item"
          :class="{ 'segment__item--on': graded === true }"
          @tap="graded = true"
        >
          老师已批改
        </view>
        <view
          class="segment__item"
          :class="{ 'segment__item--on': graded === false }"
          @tap="graded = false"
        >
          未批改（需标准答案）
        </view>
      </view>
      <view class="hint hint--sub">
        {{ graded ? '已批改：AI 只做失分归因，准确率最高（推荐）。' : '未批改：AI 需要同时参考标准答案判分，主观题存在误差。' }}
      </view>

      <view class="source-row">
        <view class="source-btn" @tap="pickAssets('sheet', 'camera')">
          <text class="source-btn__icon">◉</text>
          <text class="source-btn__text">拍照</text>
        </view>
        <view class="source-btn" @tap="pickAssets('sheet', 'album')">
          <text class="source-btn__icon">▣</text>
          <text class="source-btn__text">相册</text>
        </view>
        <view class="source-btn" @tap="pickAssets('sheet', 'file')">
          <text class="source-btn__icon">▤</text>
          <text class="source-btn__text">PDF / 图片</text>
        </view>
      </view>

      <view v-if="sheets.length" class="asset-list">
        <view v-for="item in sheets" :key="item.key" class="asset">
          <image
            v-if="isImage(item)"
            class="asset__thumb"
            :src="item.path"
            mode="aspectFill"
          />
          <view v-else class="asset__thumb asset__thumb--file">
            <text>{{ item.sourceType === 'pdf' ? 'PDF' : 'DOC' }}</text>
          </view>
          <view class="tt-flex1">
            <text class="asset__name">{{ item.name }}</text>
            <text class="tt-weak">{{ item.sizeText }} · {{ sourceLabel(item.sourceType) }}</text>
          </view>
          <text class="asset__del" @tap="removeAsset('sheet', item.key)">删除</text>
        </view>
      </view>
    </view>

    <!-- 开始分析 -->
    <view class="tt-card analyze-card">
      <view class="tt-card__title">
        <text>③ AI 失分分析</text>
        <text class="tt-card__subtitle">消耗 {{ ANALYSIS_COST }} 点数</text>
      </view>

      <!-- 空闲态 -->
      <template v-if="stage === 'idle'">
        <view class="balance-row">
          <text class="tt-muted">当前点数余额</text>
          <text class="balance-row__value">{{ credits }}</text>
        </view>
        <view class="tt-btn" :class="{ 'tt-btn--disabled': !canAnalyze }" @tap="confirmAnalyze">
          {{ canAnalyze ? '开始分析' : analyzeBlockReason }}
        </view>
        <view class="hint hint--sub">
          分析内容：逐题失分点 → 所属知识模块与章节 → 错误类型 → 针对性提升指南与周计划。
        </view>
      </template>

      <!-- 分析中 -->
      <template v-else-if="stage === 'running'">
        <view class="progress">
          <view class="progress__track">
            <view class="progress__bar" :style="{ width: progress + '%' }" />
          </view>
          <text class="progress__text">{{ progressText }}（{{ progress }}%）</text>
        </view>
        <view class="hint hint--sub">多模态模型正在逐题比对题干、作答与批改痕迹，通常需要 20~60 秒，请不要退出。</view>
      </template>

      <!-- 完成 -->
      <template v-else-if="stage === 'done' && analysis">
        <view class="result-head">
          <view>
            <text class="result-head__label">本次得分率</text>
            <text class="result-head__value">{{ percent(analysis.score_rate) }}</text>
          </view>
          <view class="result-head__right">
            <text class="tt-muted">{{ analysis.total_score }} / {{ analysis.full_score }}</text>
            <text class="tt-weak">扣 {{ ANALYSIS_COST }} 点数</text>
          </view>
        </view>

        <view class="tt-divider" />

        <text class="result-title">最需要补的 3 个知识点</text>
        <view v-for="point in topWeakPoints" :key="point.id" class="weak-mini">
          <view class="weak-mini__head">
            <text class="weak-mini__subject" :style="{ color: subjectColor(point.subject_code) }">{{ point.subject_name }}</text>
            <text class="weak-mini__module">{{ point.module }}</text>
            <text class="weak-mini__lost">-{{ point.lost_score }}分</text>
          </view>
          <text class="weak-mini__point">{{ point.knowledge_point }}</text>
        </view>

        <view class="tt-btn" @tap="openAnalysis">查看完整分析与提升指南</view>
        <view class="tt-btn tt-btn--plain reset-btn" @tap="resetAnalysis">再分析一次</view>
      </template>

      <!-- 失败 -->
      <template v-else-if="stage === 'failed'">
        <view class="tt-empty">{{ errorMessage || '分析失败，请稍后重试' }}</view>
        <view class="tt-btn tt-btn--ghost" @tap="resetAnalysis">重新分析</view>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import * as api from '@/api'
import { ANALYSIS_COST, USE_MOCK } from '@/api/config'
import { useUserStore } from '@/store/user'
import { dateOnly, percent, subjectColor } from '@/utils/format'
import type { Analysis, ExamSummary, SourceType, WeakPoint } from '@/types'

interface LocalAsset {
  key: string
  path: string
  name: string
  sourceType: SourceType
  size: number
  /** 展示用的可读体积，如 "512 KB" */
  sizeText: string
}

type Stage = 'idle' | 'running' | 'done' | 'failed'

const userStore = useUserStore()

const currentExam = ref<ExamSummary | null>(null)
const papers = ref<LocalAsset[]>([])
const sheets = ref<LocalAsset[]>([])
const graded = ref(true)
const stage = ref<Stage>('idle')
const progress = ref(0)
const progressText = ref('正在识别题目与答题卡')
const analysis = ref<Analysis | null>(null)
const errorMessage = ref('')

const credits = computed(() => userStore.credits)
const isDemo = USE_MOCK

const canAnalyze = computed(() => papers.value.length > 0 && sheets.value.length > 0 && credits.value >= ANALYSIS_COST)

const analyzeBlockReason = computed(() => {
  if (!papers.value.length) return '请先导入题目'
  if (!sheets.value.length) return '请先导入答题卡'
  if (credits.value < ANALYSIS_COST) return '点数不足，请先获取点数'
  return '开始分析'
})

const topWeakPoints = computed<WeakPoint[]>(() => {
  if (!analysis.value) return []
  return analysis.value.weak_points
    .slice()
    .sort((a, b) => b.lost_score - a.lost_score)
    .slice(0, 3)
})

/* ------------------------------------------------------------ 考试选择 */

function chooseExam(): void {
  if (!currentExam.value) {
    createExamFlow()
    return
  }
  uni.showActionSheet({
    itemList: ['新建考试', '重新选择'],
    success: (res) => {
      if (res.tapIndex === 0) createExamFlow()
      else pickExistingExam()
    },
    fail: () => {}
  })
}

async function pickExistingExam(): Promise<void> {
  const result = await api.fetchExams(1, 30)
  if (!result.items.length) {
    createExamFlow()
    return
  }
  uni.showActionSheet({
    itemList: result.items.map((e) => `${e.name}（${dateOnly(e.exam_date)}）`).slice(0, 6),
    success: (res) => {
      currentExam.value = result.items[res.tapIndex]
      resetAnalysis()
    },
    fail: () => {}
  })
}

function createExamFlow(): void {
  uni.showModal({
    title: '新建考试',
    editable: true,
    placeholderText: '例如：高二上·第一次月考',
    success: async (res) => {
      if (!res.confirm) return
      const name = (res.content || '').trim()
      if (!name) {
        uni.showToast({ title: '请输入考试名称', icon: 'none' })
        return
      }
      const today = new Date()
      const examDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
      try {
        const exam = await api.createExam({ name, exam_date: examDate, grade: '高二', total_full_score: 750 })
        currentExam.value = exam
        resetAnalysis()
        uni.showToast({ title: '考试已创建', icon: 'none' })
      } catch (e) {
        uni.showToast({ title: '创建失败，请重试', icon: 'none' })
      }
    }
  })
}

/* ------------------------------------------------------------ 资源选择 */

function isImage(item: LocalAsset): boolean {
  return item.sourceType === 'photo' || item.sourceType === 'image'
}

function sourceLabel(type: SourceType): string {
  if (type === 'photo') return '拍照'
  if (type === 'pdf') return 'PDF'
  if (type === 'docx') return 'Word'
  return '图片'
}

function sizeText(size: number): string {
  if (!size) return '--'
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function guessSourceType(path: string): SourceType {
  const lower = path.toLowerCase()
  if (lower.endsWith('.pdf')) return 'pdf'
  if (lower.endsWith('.doc') || lower.endsWith('.docx')) return 'docx'
  return 'image'
}

function decorate(asset: Omit<LocalAsset, 'sizeText'>): LocalAsset {
  return { ...asset, sizeText: sizeText(asset.size) }
}

async function pickAssets(target: 'paper' | 'sheet', from: 'camera' | 'album' | 'file'): Promise<void> {
  if (!currentExam.value) {
    uni.showToast({ title: '请先选择或新建考试', icon: 'none' })
    return
  }

  if (from === 'file') {
    chooseFiles(target)
    return
  }

  uni.chooseImage({
    count: from === 'camera' ? 1 : 9,
    sizeType: ['compressed', 'original'],
    sourceType: [from === 'camera' ? 'camera' : 'album'],
    success: (res) => {
      // 不同平台/版本下 tempFilePaths 可能是字符串或数组，统一成数组处理
      const rawPaths = res.tempFilePaths as unknown
      const paths: string[] = Array.isArray(rawPaths) ? rawPaths : rawPaths ? [rawPaths as string] : []
      const rawFiles = res.tempFiles as unknown
      const files: any[] = Array.isArray(rawFiles) ? rawFiles : rawFiles ? [rawFiles] : []
      paths.forEach((path: string, index: number) => {
        enqueue(target, {
          path,
          name: `拍摄_${Date.now()}_${index + 1}.jpg`,
          sourceType: 'photo',
          size: files[index]?.size || 0
        })
      })
    },
    fail: () => {}
  })
}

function chooseFiles(target: 'paper' | 'sheet'): void {
  const choose = (uni as any).chooseFile
  if (typeof choose !== 'function') {
    uni.showToast({ title: '当前平台暂不支持文件选择，请改用拍照或相册', icon: 'none' })
    return
  }
  choose({
    count: 9,
    extension: ['.pdf', '.doc', '.docx', '.jpg', '.jpeg', '.png', '.webp'],
    success: (res: any) => {
      const paths: string[] = res.tempFilePaths || []
      const files: any[] = res.tempFiles || []
      paths.forEach((path, index) => {
        const info = files[index] || {}
        enqueue(target, {
          path,
          name: info.name || `文件_${Date.now()}_${index + 1}`,
          sourceType: guessSourceType(info.name || path),
          size: info.size || 0
        })
      })
    },
    fail: () => {
      uni.showToast({ title: '已取消选择', icon: 'none' })
    }
  })
}

/** 上传并登记到列表 */
async function enqueue(
  target: 'paper' | 'sheet',
  raw: { path: string; name: string; sourceType: SourceType; size: number }
): Promise<void> {
  uni.showLoading({ title: '上传中', mask: true })
  try {
    const uploaded = await api.uploadFile(raw.path, target === 'paper' ? 'paper' : 'answer_sheet')
    const asset = decorate({
      key: uploaded.object_key,
      path: uploaded.url || raw.path,
      name: raw.name,
      sourceType: raw.sourceType,
      size: raw.size || uploaded.size
    })
    if (target === 'paper') {
      papers.value = papers.value.concat([asset])
      await api.createPaper(currentExam.value!.id, {
        subject_code: 'unknown',
        title: raw.name,
        source_type: raw.sourceType,
        object_keys: [uploaded.object_key]
      })
    } else {
      sheets.value = sheets.value.concat([asset])
      await api.createAnswerSheet(currentExam.value!.id, {
        subject_code: 'unknown',
        source_type: raw.sourceType,
        is_graded: graded.value,
        object_keys: [uploaded.object_key]
      })
    }
    uni.hideLoading()
    uni.showToast({ title: '已添加', icon: 'none' })
  } catch (e: any) {
    uni.hideLoading()
    uni.showToast({ title: e?.message || '上传失败', icon: 'none' })
  }
}

function removeAsset(target: 'paper' | 'sheet', key: string): void {
  if (target === 'paper') papers.value = papers.value.filter((item) => item.key !== key)
  else sheets.value = sheets.value.filter((item) => item.key !== key)
}

/* -------------------------------------------------------------- 分析 */

function confirmAnalyze(): void {
  if (!canAnalyze.value) {
    if (credits.value < ANALYSIS_COST) {
      uni.showModal({
        title: '点数不足',
        content: `本次分析需要 ${ANALYSIS_COST} 点数，当前余额 ${credits.value} 点。可以观看激励视频广告获取，或直接充值。`,
        confirmText: '去获取',
        success: (res) => {
          if (res.confirm) uni.switchTab({ url: '/pages/account/index' })
        }
      })
      return
    }
    uni.showToast({ title: analyzeBlockReason.value, icon: 'none' })
    return
  }

  uni.showModal({
    title: '确认开始分析',
    content: `本次分析将消耗 ${ANALYSIS_COST} 点数，分析后余额 ${credits.value - ANALYSIS_COST} 点。分析结果会给出失分知识点、所属模块与提升指南。`,
    confirmText: '开始',
    success: (res) => {
      if (res.confirm) runAnalysis()
    }
  })
}

async function runAnalysis(): Promise<void> {
  if (!currentExam.value) return
  stage.value = 'running'
  progress.value = 0
  errorMessage.value = ''

  const timer = startProgressTimer()

  try {
    const created = await api.createAnalysis(currentExam.value.id, undefined, true)
    userStore.setCredits(created.credits_balance)

    let result = await api.fetchAnalysis(created.analysis_id)
    let guard = 0
    while (result.status === 'pending' || result.status === 'running') {
      if (guard++ > 40) throw new Error('分析超时，请稍后重试')
      await sleep(USE_MOCK ? 600 : 3000)
      result = await api.fetchAnalysis(created.analysis_id)
    }
    if (result.status === 'failed') throw new Error('模型分析失败，请更换更清晰的图片后重试')

    clearInterval(timer)
    progress.value = 100
    progressText.value = '分析完成'
    analysis.value = result
    stage.value = 'done'
    await userStore.refreshMe().catch(() => {})
    await userStore.refreshAdProgress().catch(() => {})
  } catch (e: any) {
    clearInterval(timer)
    errorMessage.value = e?.message || '分析失败'
    stage.value = 'failed'
  }
}

function startProgressTimer(): ReturnType<typeof setInterval> {
  const steps: [number, string][] = [
    [12, '正在上传与预处理图片'],
    [30, '正在识别题目与考点'],
    [52, '正在比对作答与批改痕迹'],
    [72, '正在定位失分知识点与所属模块'],
    [88, '正在生成提升指南与周计划']
  ]
  let index = 0
  return setInterval(() => {
    if (index < steps.length) {
      progress.value = steps[index][0]
      progressText.value = steps[index][1]
      index += 1
    } else if (progress.value < 95) {
      progress.value += 1
    }
  }, USE_MOCK ? 420 : 2200)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function resetAnalysis(): void {
  stage.value = 'idle'
  progress.value = 0
  analysis.value = null
  errorMessage.value = ''
}

function openAnalysis(): void {
  if (!analysis.value || !currentExam.value) return
  uni.navigateTo({
    url: `/pages/analysis/index?exam_id=${currentExam.value.id}&analysis_id=${analysis.value.id}&name=${encodeURIComponent(currentExam.value.name)}`
  })
}

/* -------------------------------------------------------------- 生命周期 */

async function loadExams(): Promise<void> {
  if (currentExam.value) return
  const result = await api.fetchExams(1, 30)
  if (result.items.length) currentExam.value = result.items[0]
}

onLoad(() => {
  if (userStore.isLogin) {
    userStore.refreshMe().catch(() => {})
  }
  loadExams().catch(() => {})
})

onShow(() => {
  loadExams().catch(() => {})
})
</script>

<style lang="scss" scoped>
.exam-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.exam-bar__label {
  display: block;
  font-size: 22rpx;
  color: $tt-text-weak;
}

.exam-bar__name {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: $tt-text;
  margin-top: 8rpx;
}

.exam-bar__name.is-empty {
  color: $tt-primary;
}

.exam-bar__date {
  display: block;
  margin-top: 6rpx;
}

.exam-bar__action {
  font-size: 26rpx;
  color: $tt-primary;
  padding: 12rpx 24rpx;
  background-color: $tt-primary-light;
  border-radius: 28rpx;
}

.hint {
  font-size: 24rpx;
  color: $tt-text-sub;
  line-height: 1.6;
  margin-bottom: 20rpx;
}

.hint--sub {
  color: $tt-text-weak;
  font-size: 22rpx;
  margin-top: 16rpx;
  margin-bottom: 0;
}

.source-row {
  display: flex;
  gap: 16rpx;
}

.source-btn {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24rpx 0;
  background-color: #f6f8fc;
  border-radius: 16rpx;
  border: 1rpx dashed #ccd6ea;

  &:active {
    background-color: $tt-primary-light;
  }
}

.source-btn__icon {
  font-size: 36rpx;
  color: $tt-primary;
  line-height: 1;
  margin-bottom: 10rpx;
}

.source-btn__text {
  font-size: 24rpx;
  color: $tt-text-sub;
}

.segment {
  display: flex;
  background-color: #f1f4fa;
  border-radius: 16rpx;
  padding: 6rpx;
  margin-bottom: 8rpx;
}

.segment__item {
  flex: 1;
  text-align: center;
  height: 68rpx;
  line-height: 68rpx;
  font-size: 26rpx;
  color: $tt-text-sub;
  border-radius: 12rpx;
}

.segment__item--on {
  background-color: #ffffff;
  color: $tt-primary;
  font-weight: 600;
  box-shadow: 0 2rpx 8rpx rgba(76, 111, 255, 0.12);
}

.asset-list {
  margin-top: 20rpx;
}

.asset {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-top: 1rpx solid $tt-border;
}

.asset__thumb {
  width: 88rpx;
  height: 88rpx;
  border-radius: 12rpx;
  margin-right: 20rpx;
  background-color: #eef1f8;
  flex-shrink: 0;
}

.asset__thumb--file {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  font-weight: 600;
  color: $tt-primary;
  background-color: $tt-primary-light;
}

.asset__name {
  display: block;
  font-size: 26rpx;
  color: $tt-text;
  margin-bottom: 6rpx;
}

.asset__del {
  font-size: 24rpx;
  color: $tt-danger;
  padding: 10rpx 16rpx;
}

.analyze-card {
  margin-bottom: 40rpx;
}

.balance-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 20rpx;
}

.balance-row__value {
  font-size: 40rpx;
  font-weight: 700;
  color: $tt-primary;
}

.progress__track {
  height: 16rpx;
  background-color: #eef1f8;
  border-radius: 8rpx;
  overflow: hidden;
}

.progress__bar {
  height: 100%;
  background: linear-gradient(90deg, #4c6fff, #6d8bff);
  border-radius: 8rpx;
  transition: width 0.4s ease;
}

.progress__text {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: $tt-text-sub;
}

.result-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.result-head__label {
  display: block;
  font-size: 22rpx;
  color: $tt-text-weak;
}

.result-head__value {
  display: block;
  font-size: 56rpx;
  font-weight: 700;
  color: $tt-primary;
}

.result-head__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.result-title {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: $tt-text;
  margin-bottom: 16rpx;
}

.weak-mini {
  padding: 16rpx 20rpx;
  background-color: #f8faff;
  border-radius: 14rpx;
  margin-bottom: 12rpx;
}

.weak-mini__head {
  display: flex;
  align-items: center;
}

.weak-mini__subject {
  font-size: 24rpx;
  font-weight: 600;
  margin-right: 14rpx;
}

.weak-mini__module {
  flex: 1;
  font-size: 22rpx;
  color: $tt-text-sub;
}

.weak-mini__lost {
  font-size: 22rpx;
  color: $tt-danger;
  font-weight: 600;
}

.weak-mini__point {
  display: block;
  font-size: 24rpx;
  color: $tt-text;
  margin-top: 8rpx;
  line-height: 1.5;
}

.reset-btn {
  margin-top: 16rpx;
}
</style>
