<template>
  <view class="chart">
    <view class="chart__wrap" :style="{ height: height + 'px', width: width + 'px' }">
      <canvas
        :canvas-id="canvasId"
        :id="canvasId"
        class="chart__canvas"
        :style="{ width: width + 'px', height: height + 'px' }"
        @touchstart="handleTouch"
        @touchmove="handleTouch"
        @touchend="handleTouchEnd"
        @touchcancel="handleTouchEnd"
      />
      <view v-if="tooltipIndex >= 0" class="chart__tooltip" :style="tooltipStyle">
        <view class="chart__tooltip-title">{{ labels[tooltipIndex] }}</view>
        <view v-for="item in tooltipItems" :key="item.name" class="chart__tooltip-row">
          <view class="chart__tooltip-dot" :style="{ background: item.color }" />
          <text class="chart__tooltip-name">{{ item.name }}</text>
          <text class="chart__tooltip-value">{{ item.text }}</text>
        </view>
      </view>
    </view>

    <view class="chart__legend">
      <view
        v-for="item in series"
        :key="item.name"
        class="chart__legend-item"
        @tap="toggleSeries(item.name)"
      >
        <view
          class="chart__legend-dot"
          :style="{ background: isHidden(item.name) ? '#cbd5e1' : item.color }"
        />
        <text :class="['chart__legend-text', isHidden(item.name) ? 'is-off' : '']">{{ item.name }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onMounted, ref, watch } from 'vue'
import type { ChartSeries } from '@/types/chart'

const props = withDefaults(
  defineProps<{
    /** 绘图区宽度（px），由父组件按屏幕宽度计算后传入 */
    width: number
    /** 绘图区高度（px） */
    height?: number
    labels: string[]
    series: ChartSeries[]
    /** 纵轴是否按百分比显示（默认 true，取值 0~1） */
    percentAxis?: boolean
  }>(),
  {
    height: 220,
    percentAxis: true
  }
)

const emit = defineEmits<{ (e: 'point-click', index: number): void }>()

const instance = getCurrentInstance()
const canvasId = `tt-line-${Math.random().toString(36).slice(2, 9)}`
const hiddenNames = ref<string[]>([])
const tooltipIndex = ref(-1)

const PAD = { left: 44, right: 16, top: 18, bottom: 32 }

const visibleSeries = computed(() => props.series.filter((s) => !isHidden(s.name)))

const tooltipItems = computed(() => {
  if (tooltipIndex.value < 0) return []
  return visibleSeries.value.map((s) => {
    const value = s.data[tooltipIndex.value]
    return {
      name: s.name,
      color: s.color,
      text: value === null || value === undefined ? '--' : props.percentAxis ? `${(value * 100).toFixed(1)}%` : String(value)
    }
  })
})

const tooltipStyle = computed(() => {
  if (tooltipIndex.value < 0) return {}
  const n = Math.max(props.labels.length, 2)
  const plotW = props.width - PAD.left - PAD.right
  const x = PAD.left + (plotW * tooltipIndex.value) / (n - 1)
  const half = 90
  const left = Math.min(Math.max(x - half, 4), Math.max(props.width - half * 2 - 4, 4))
  return { left: `${left}px`, top: '4px' }
})

function isHidden(name: string): boolean {
  return hiddenNames.value.indexOf(name) > -1
}

function toggleSeries(name: string): void {
  // 总分线不允许隐藏，保证任何时候都有参照
  if (name === '总分') return
  const list = hiddenNames.value.slice()
  const idx = list.indexOf(name)
  if (idx > -1) list.splice(idx, 1)
  else list.push(name)
  hiddenNames.value = list
  nextTick(draw)
}

function yLabel(value: number): string {
  return props.percentAxis ? `${Math.round(value * 100)}%` : String(value)
}

function draw(): void {
  const ctx = uni.createCanvasContext(canvasId, instance)
  const w = props.width
  const h = props.height
  const plotW = w - PAD.left - PAD.right
  const plotH = h - PAD.top - PAD.bottom
  const series = visibleSeries.value
  const n = props.labels.length

  ctx.clearRect(0, 0, w, h)
  if (n === 0 || series.length === 0) {
    ctx.setFillStyle('#9E9BB0')
    ctx.setFontSize(12)
    ctx.setTextAlign('center')
    ctx.fillText('暂无成绩数据', w / 2, h / 2)
    ctx.draw()
    return
  }

  // 纵轴范围：默认 0~100%，若数据普遍偏低则从 40% 起，让波动更明显
  let minRate = 1
  series.forEach((s) => {
    s.data.forEach((v) => {
      if (v !== null && v !== undefined && v < minRate) minRate = v
    })
  })
  const yMin = props.percentAxis ? Math.max(0, Math.floor((minRate - 0.08) * 10) / 10) : 0
  const yMax = props.percentAxis ? 1 : Math.max(...series.flatMap((s) => s.data.filter((v): v is number => v !== null)))
  const span = yMax - yMin || 1
  const yOf = (v: number) => PAD.top + plotH * (1 - (v - yMin) / span)
  const xOf = (i: number) => PAD.left + (n === 1 ? plotW / 2 : (plotW * i) / (n - 1))

  // 网格线与纵轴刻度
  const ticks = 4
  for (let t = 0; t <= ticks; t++) {
    const value = yMin + (span * t) / ticks
    const y = yOf(value)
    ctx.beginPath()
    ctx.setStrokeStyle(t === 0 ? '#E8ECF5' : '#F1F4FA')
    ctx.setLineWidth(1)
    ctx.moveTo(PAD.left, y)
    ctx.lineTo(PAD.left + plotW, y)
    ctx.stroke()

    ctx.setFillStyle('#9E9BB0')
    ctx.setFontSize(10)
    ctx.setTextAlign('right')
    ctx.fillText(yLabel(value), PAD.left - 8, y + 3.5)
  }

  // 横轴标签：点多时隔一个显示，避免拥挤
  const labelStep = n > 7 ? 2 : 1
  ctx.setTextAlign('center')
  ctx.setFillStyle('#9E9BB0')
  ctx.setFontSize(10)
  props.labels.forEach((label, i) => {
    if (i % labelStep !== 0 && i !== n - 1) return
    const text = label.length > 6 ? `${label.slice(0, 6)}…` : label
    ctx.fillText(text, xOf(i), h - PAD.bottom + 16)
  })

  // 选中态的竖线（先画，避免盖住折线）
  if (tooltipIndex.value >= 0 && tooltipIndex.value < n) {
    const x = xOf(tooltipIndex.value)
    ctx.beginPath()
    ctx.setStrokeStyle('#C9D2E6')
    ctx.setLineWidth(1)
    ctx.moveTo(x, PAD.top)
    ctx.lineTo(x, PAD.top + plotH)
    ctx.stroke()
  }

  // 折线
  series.forEach((s) => {
    ctx.beginPath()
    ctx.setStrokeStyle(s.color)
    ctx.setLineWidth(s.lineWidth ?? 2)
    ctx.setLineCap('round')
    ctx.setLineJoin('round')
    let started = false
    s.data.forEach((v, i) => {
      if (v === null || v === undefined) {
        started = false
        return
      }
      const x = xOf(i)
      const y = yOf(v)
      if (!started) {
        ctx.moveTo(x, y)
        started = true
      } else {
        ctx.lineTo(x, y)
      }
    })
    ctx.stroke()

    // 数据点
    s.data.forEach((v, i) => {
      if (v === null || v === undefined) return
      const x = xOf(i)
      const y = yOf(v)
      const active = i === tooltipIndex.value
      const r = active ? 5 : 3
      ctx.beginPath()
      ctx.setFillStyle('#FFFFFF')
      ctx.arc(x, y, r + 1.5, 0, Math.PI * 2)
      ctx.fill()
      ctx.beginPath()
      ctx.setFillStyle(s.color)
      ctx.arc(x, y, r, 0, Math.PI * 2)
      ctx.fill()
    })
  })

  ctx.draw()
}

function locate(touchX: number): number {
  const n = props.labels.length
  if (n === 0) return -1
  const plotW = props.width - PAD.left - PAD.right
  if (n === 1) return 0
  const step = plotW / (n - 1)
  const index = Math.round((touchX - PAD.left) / step)
  return Math.min(n - 1, Math.max(0, index))
}

function handleTouch(e: any): void {
  const touch = e.touches && e.touches[0]
  if (!touch) return
  const index = locate(touch.x !== undefined ? touch.x : touch.clientX)
  if (index !== tooltipIndex.value) {
    tooltipIndex.value = index
    nextTick(draw)
  }
}

function handleTouchEnd(): void {
  const index = tooltipIndex.value
  if (index >= 0) emit('point-click', index)
  tooltipIndex.value = -1
  nextTick(draw)
}

onMounted(() => {
  nextTick(draw)
})

watch(
  () => [props.labels, props.series, props.width, props.height],
  () => nextTick(draw),
  { deep: true }
)

defineExpose({ redraw: draw })
</script>

<style lang="scss" scoped>
.chart {
  width: 100%;
}

.chart__wrap {
  position: relative;
}

.chart__canvas {
  display: block;
}

.chart__tooltip {
  position: absolute;
  min-width: 180rpx;
  max-width: 340rpx;
  padding: 12rpx 16rpx;
  background-color: rgba(27, 33, 56, 0.92);
  border-radius: 12rpx;
  z-index: 10;
}

.chart__tooltip-title {
  color: #ffffff;
  font-size: 22rpx;
  font-weight: 600;
  margin-bottom: 6rpx;
}

.chart__tooltip-row {
  display: flex;
  align-items: center;
  height: 32rpx;
}

.chart__tooltip-dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 6rpx;
  margin-right: 10rpx;
}

.chart__tooltip-name {
  flex: 1;
  color: rgba(255, 255, 255, 0.75);
  font-size: 20rpx;
  margin-right: 12rpx;
}

.chart__tooltip-value {
  color: #ffffff;
  font-size: 20rpx;
  font-weight: 600;
}

.chart__legend {
  display: flex;
  flex-wrap: wrap;
  margin-top: 12rpx;
}

.chart__legend-item {
  display: flex;
  align-items: center;
  margin-right: 28rpx;
  margin-bottom: 8rpx;
}

.chart__legend-dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 7rpx;
  margin-right: 8rpx;
}

.chart__legend-text {
  font-size: 22rpx;
  color: $tt-text-sub;
}

.chart__legend-text.is-off {
  color: #c3c9d6;
  text-decoration: line-through;
}
</style>
