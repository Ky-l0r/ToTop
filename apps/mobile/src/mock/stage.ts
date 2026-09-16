/**
 * 分阶段解锁的演示数据与内存态模拟。
 *
 * 演示模式（USE_MOCK）下，解锁状态保存在内存里，浏览器刷新即重置——
 * 这样每次打开都能完整走一遍「看广告 → 解锁」的流程，便于演示与自测。
 */
import type { AnalysisStage, StageNo, UnlockMethod } from '@/types/stage'

interface StageState {
  watched: number
  unlocked: boolean
  unlock_method: UnlockMethod | null
}

/** analysisId -> stage -> 状态 */
const memory: Record<string, Record<number, StageState>> = {}
/** 已处理过的广告事件 ID，用于模拟服务端幂等 */
const seenEvents: Record<string, true> = {}

const AD_REQUIRED: Record<number, number> = { 1: 1, 2: 2, 3: 2 }

/* ------------------------------------------------------------------ 内容 */

const STAGE1 = {
  summary:
    '本次总分得分率 66.4%，比上次提升 2.1 个百分点。数学与生物是主要拖累项，失分集中在含参讨论不完整与模型理解偏差两类问题上——属于方法性失分，不是知识空白，专项训练 2 至 3 周可回收大部分分数。语文古诗文与英语阅读基本达标。',
  score_rate: 0.664,
  total_score: 498,
  full_score: 750,
  level_text: '及格',
  top_subjects: [
    { name: '英语', rate: 0.747 },
    { name: '语文', rate: 0.72 },
    { name: '化学', rate: 0.68 }
  ]
}

const STAGE2 = {
  total_lost: 58,
  weak_questions: [
    {
      question_no: '数学 21',
      subject_name: '数学',
      module: '解析几何',
      knowledge_point: '直线与椭圆联立后的韦达定理与弦长公式',
      lost_score: 12,
      severity: 'high' as const,
      error_type: '方法选择不当',
      brief: '联立后硬解方程导致计算失控，没有使用「设而不求」。'
    },
    {
      question_no: '数学 18(2)',
      subject_name: '数学',
      module: '函数与导数',
      knowledge_point: '含参函数的单调区间与极值',
      lost_score: 10,
      severity: 'high' as const,
      error_type: '分类讨论不完整',
      brief: '只讨论了 a>0 的情形，漏掉 a≤0 时无极值点的情况。'
    },
    {
      question_no: '物理 15(2)',
      subject_name: '物理',
      module: '电磁学',
      knowledge_point: '导体棒切割磁感线的能量转化',
      lost_score: 8,
      severity: 'high' as const,
      error_type: '模型理解偏差',
      brief: '写动能定理时漏掉安培力做功，能量出口判断错误。'
    },
    {
      question_no: '语文 作文',
      subject_name: '语文',
      module: '写作',
      knowledge_point: '任务驱动型作文的论证层次',
      lost_score: 7,
      severity: 'medium' as const,
      error_type: '结构扁平',
      brief: '三个分论点并列展开，缺少递进与辩证分析。'
    },
    {
      question_no: '生物 32(3)',
      subject_name: '生物',
      module: '遗传与进化',
      knowledge_point: '两对相对性状的概率计算',
      lost_score: 6,
      severity: 'medium' as const,
      error_type: '计算失误',
      brief: '直接套 9:3:3:1，没有按「拆对法」分别计算再相乘。'
    },
    {
      question_no: '化学 17(2)',
      subject_name: '化学',
      module: '化学反应原理',
      knowledge_point: '平衡常数计算与三段式',
      lost_score: 6,
      severity: 'medium' as const,
      error_type: '计算规范缺失',
      brief: '三段式起始量代错，导致 K 值整体偏大。'
    },
    {
      question_no: '语文 13(1)',
      subject_name: '语文',
      module: '古诗文',
      knowledge_point: '文言实词的一词多义',
      lost_score: 5,
      severity: 'medium' as const,
      error_type: '实词积累不足',
      brief: '把「因」译为「因为」，未结合语境判断为「沿袭」。'
    },
    {
      question_no: '英语 写作',
      subject_name: '英语',
      module: '写作',
      knowledge_point: '读后续写的情节与情感呼应',
      lost_score: 4,
      severity: 'low' as const,
      error_type: '表达单一',
      brief: '情节推进尚可，但缺少心理描写，句式重复。'
    }
  ]
}

const STAGE3 = {
  modules: [
    { module: '函数与导数', subject_name: '数学', lost_score_total: 10, priority: 1, reason: '含参讨论是高频考点，且与其他模块强关联，补齐后收益最大。' },
    { module: '解析几何', subject_name: '数学', lost_score_total: 12, priority: 1, reason: '失分最多，但属于「方法问题」，掌握设而不求即可快速回收。' },
    { module: '电磁学', subject_name: '物理', lost_score_total: 8, priority: 2, reason: '能量守恒与安培力做功的对应关系没有固化，属模型理解问题。' },
    { module: '写作', subject_name: '语文', lost_score_total: 7, priority: 3, reason: '论证层次需要长期训练，见效慢但影响作文上限。' },
    { module: '遗传与进化', subject_name: '生物', lost_score_total: 6, priority: 3, reason: '纯计算与规范问题，限时训练即可显著改善。' }
  ],
  suggestions: [
    '每天固定 40 分钟做「数学专项」：含参单调性 2 题 + 圆锥曲线 1 题，做完必须自查分类是否完整。',
    '物理把电磁感应四类模型各画一张能量流向图，做题前先判断能量出口再动笔。',
    '化学所有平衡计算强制写三段式，不跳步；生物遗传题用拆对法限时 3 分钟。',
    '语文每周写 1 篇作文提纲（只写论点与层次关系），英语背 10 组心理描写句块。'
  ],
  improvement_plan: {
    summary:
      '本次失分以「方法选择」与「模型理解」为主，属于可通过专项训练快速回收的类型。建议按「数学 → 物理 → 化生 → 语文英语」的顺序推进，3 周内总分得分率目标提升到 70% 以上。',
    priority_order: [
      '数学·导数与圆锥曲线（22 分）',
      '物理·电磁感应能量分析（8 分）',
      '生物·遗传概率计算（6 分）',
      '化学·平衡计算规范（6 分）',
      '语文·文言实词与作文结构（12 分）'
    ],
    steps: [
      {
        title: '第 1 步：堵住数学的两个大出血点',
        detail: '导数含参讨论与圆锥曲线设而不求，先重建模板再做近三年真题同类题，每题限时 12 分钟。',
        estimated_hours: 8,
        resources: ['选择性必修二 5.3 导数在研究函数中的应用', '近三年真题分类汇编·圆锥曲线']
      },
      {
        title: '第 2 步：物理电磁感应的能量视角',
        detail: '把安培力做功与焦耳热的对应关系用能量流向图固化，重做 10 道导体棒模型题。',
        estimated_hours: 5,
        resources: ['必修三 电磁感应章节', '能量守恒专题讲义']
      },
      {
        title: '第 3 步：化生两科的规范与计算',
        detail: '化学平衡题强制三段式；生物遗传题每天 5 道限时训练。',
        estimated_hours: 4,
        resources: ['化学反应原理 第二章', '遗传学专题练习册']
      }
    ],
    weekly_plan: [
      { week: 1, focus: '数学导数与圆锥曲线模板重建', target: '同类题正确率从 45% 提升到 70%' },
      { week: 2, focus: '物理电磁感应 + 化学平衡规范', target: '两科各完成 10 道专项题，错题率低于 30%' },
      { week: 3, focus: '生物遗传计算 + 语英积累', target: '遗传计算限时正确率 85%' },
      { week: 4, focus: '综合限时套卷 + 错题回炉', target: '总分得分率提升到 70% 以上' }
    ]
  }
}

const CONTENT: Record<number, unknown> = { 1: STAGE1, 2: STAGE2, 3: STAGE3 }

const NAMES: Record<number, string> = { 1: '基础分析', 2: '进阶分析', 3: '归纳总结' }

/* ------------------------------------------------------------------ 状态 */

function stateOf(analysisId: string): Record<number, StageState> {
  if (!memory[analysisId]) {
    memory[analysisId] = {
      1: { watched: 0, unlocked: false, unlock_method: null },
      2: { watched: 0, unlocked: false, unlock_method: null },
      3: { watched: 0, unlocked: false, unlock_method: null }
    }
  }
  return memory[analysisId]
}

/** 组装三个阶段（未解锁的 content 为 null，与真实后端行为一致） */
export function mockStages(analysisId: string): AnalysisStage[] {
  const state = stateOf(analysisId)
  return ([1, 2, 3] as StageNo[]).map((stage) => {
    const item = state[stage]
    return {
      stage,
      name: NAMES[stage],
      ad_required: AD_REQUIRED[stage],
      watched: item.watched,
      unlocked: item.unlocked,
      unlock_method: item.unlock_method,
      content: item.unlocked ? (CONTENT[stage] as any) : null
    }
  })
}

export interface MockUnlockResult {
  stage: StageNo
  watched: number
  ad_required: number
  unlocked: boolean
  credited: boolean
  duplicated: boolean
  unlock_method: UnlockMethod | null
  content: unknown
}

/** 模拟看一次广告的解锁上报（含 event_id 幂等） */
export function mockReportAd(
  analysisId: string,
  stage: StageNo,
  eventId: string
): MockUnlockResult {
  const state = stateOf(analysisId)
  const item = state[stage]
  const required = AD_REQUIRED[stage]

  if (seenEvents[eventId]) {
    return {
      stage,
      watched: item.watched,
      ad_required: required,
      unlocked: item.unlocked,
      credited: false,
      duplicated: true,
      unlock_method: item.unlock_method,
      content: item.unlocked ? CONTENT[stage] : null
    }
  }
  seenEvents[eventId] = true

  if (!item.unlocked) {
    item.watched += 1
    if (item.watched >= required) {
      item.unlocked = true
      item.unlock_method = 'ad'
    }
  }

  return {
    stage,
    watched: item.watched,
    ad_required: required,
    unlocked: item.unlocked,
    credited: true,
    duplicated: false,
    unlock_method: item.unlock_method,
    content: item.unlocked ? CONTENT[stage] : null
  }
}

/** 模拟用点数跳过广告解锁 */
export function mockUnlockByCredit(analysisId: string, stage: StageNo): MockUnlockResult {
  const state = stateOf(analysisId)
  const item = state[stage]
  item.unlocked = true
  item.unlock_method = 'credit'
  item.watched = AD_REQUIRED[stage]
  return {
    stage,
    watched: item.watched,
    ad_required: AD_REQUIRED[stage],
    unlocked: true,
    credited: true,
    duplicated: false,
    unlock_method: 'credit',
    content: CONTENT[stage]
  }
}

/** 演示模式下返回完整的（全部已解锁的）三阶段，供「一键体验」使用 */
export function mockAllUnlocked(analysisId: string): AnalysisStage[] {
  const state = stateOf(analysisId)
  ;([1, 2, 3] as StageNo[]).forEach((stage) => {
    state[stage].unlocked = true
    state[stage].unlock_method = 'ad'
    state[stage].watched = AD_REQUIRED[stage]
  })
  return mockStages(analysisId)
}
