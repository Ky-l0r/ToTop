import type {
  AdProgress,
  Analysis,
  CreditLedgerItem,
  ExamSummary,
  Plan,
  SubjectMeta,
  TrendData,
  User
} from '@/types'

/**
 * 演示数据（骨架阶段使用）。
 * API 不可用时自动降级到这里，保证 H5 打开就有完整的图表与结果可看。
 * 数据用确定性算法生成，不使用 Math.random，保证每次刷新一致。
 */

export const MOCK_SUBJECTS: SubjectMeta[] = [
  { code: 'chinese', name: '语文', modules: [{ name: '现代文阅读', chapters: ['论述类文本', '文学类文本'] }, { name: '古诗文', chapters: ['文言文阅读', '诗歌鉴赏'] }, { name: '写作', chapters: ['任务驱动型作文'] }] },
  { code: 'math', name: '数学', modules: [{ name: '函数与导数', chapters: ['函数性质', '导数应用'] }, { name: '解析几何', chapters: ['圆锥曲线', '直线与圆'] }, { name: '概率统计', chapters: ['排列组合', '统计推断'] }] },
  { code: 'english', name: '英语', modules: [{ name: '阅读理解', chapters: ['细节理解', '推理判断'] }, { name: '写作', chapters: ['应用文', '读后续写'] }] },
  { code: 'physics', name: '物理', modules: [{ name: '力学', chapters: ['牛顿定律', '机械能'] }, { name: '电磁学', chapters: ['电场', '电磁感应'] }] },
  { code: 'chemistry', name: '化学', modules: [{ name: '化学反应原理', chapters: ['化学平衡', '电化学'] }, { name: '有机化学', chapters: ['烃的衍生物'] }] },
  { code: 'biology', name: '生物', modules: [{ name: '遗传与进化', chapters: ['孟德尔定律', '基因表达'] }, { name: '稳态与调节', chapters: ['神经调节', '体液调节'] }] }
]

export const MOCK_USER: User = {
  id: 'u_demo_0001',
  phone: '138****8888',
  nickname: '同学你好',
  avatar_url: '',
  credits: 2,
  invite_code: 'TOTOP8X',
  created_at: '2026-08-20T09:00:00+08:00'
}

const EXAM_NAMES = [
  '高二上·开学摸底考',
  '高二上·第一次月考',
  '高二上·期中考',
  '高二上·第二次月考',
  '高二上·期末考',
  '高二下·开学考',
  '高二下·第一次月考',
  '高二下·期中考'
]

const SUBJECT_WEIGHT: Record<string, number> = {
  chinese: 150,
  math: 150,
  english: 150,
  physics: 100,
  chemistry: 100,
  biology: 100
}

/** 生成一条平滑但带波动的得分率曲线，base 为起始水平，slope 为整体走向 */
function rateAt(index: number, seed: number, base: number, slope: number): number {
  const wave = Math.sin((index + seed) * 0.9) * 0.045 + Math.cos((index * 1.7 + seed) * 0.6) * 0.025
  const value = base + slope * index + wave
  return Math.min(0.98, Math.max(0.35, Number(value.toFixed(4))))
}

export const MOCK_TREND: TrendData = (() => {
  const points = EXAM_NAMES.map((name, i) => {
    const subjects: Record<string, number> = {}
    MOCK_SUBJECTS.forEach((s, si) => {
      const base = 0.62 + si * 0.012
      const slope = si % 3 === 0 ? 0.028 : si % 3 === 1 ? 0.012 : 0.021
      subjects[s.code] = rateAt(i, si + 1, base, slope)
    })
    let weighted = 0
    let weightSum = 0
    Object.keys(subjects).forEach((code) => {
      const w = SUBJECT_WEIGHT[code] ?? 100
      weighted += subjects[code] * w
      weightSum += w
    })
    const month = 3 + Math.floor(i / 1.5)
    const day = 8 + (i % 3) * 7
    return {
      exam_id: `exam_${String(i + 1).padStart(2, '0')}`,
      exam_name: name,
      exam_date: `2026-${String(Math.min(12, month)).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
      total_rate: Number((weighted / weightSum).toFixed(4)),
      subjects
    }
  })
  return { points, subjects: MOCK_SUBJECTS.map((s) => ({ code: s.code, name: s.name })) }
})()

export const MOCK_EXAMS: ExamSummary[] = MOCK_TREND.points
  .map((p, i): ExamSummary => ({
    id: p.exam_id,
    name: p.exam_name,
    exam_date: p.exam_date,
    grade: '高二',
    total_full_score: 750,
    subject_count: MOCK_SUBJECTS.length,
    analysis_status: i >= 5 ? 'succeeded' : 'none',
    created_at: `${p.exam_date}T20:10:00+08:00`
  }))
  .reverse()

export const MOCK_ANALYSIS: Analysis = {
  id: 'ana_demo_0001',
  exam_id: 'exam_08',
  status: 'succeeded',
  total_score: 498,
  full_score: 750,
  score_rate: 0.664,
  subjects: [
    { subject_code: 'chinese', subject_name: '语文', score: 108, full_score: 150, rate: 0.72, weak_module_names: ['古诗文', '写作'] },
    { subject_code: 'math', subject_name: '数学', score: 88, full_score: 150, rate: 0.587, weak_module_names: ['函数与导数', '解析几何'] },
    { subject_code: 'english', subject_name: '英语', score: 112, full_score: 150, rate: 0.747, weak_module_names: ['写作'] },
    { subject_code: 'physics', subject_name: '物理', score: 63, full_score: 100, rate: 0.63, weak_module_names: ['电磁学'] },
    { subject_code: 'chemistry', subject_name: '化学', score: 68, full_score: 100, rate: 0.68, weak_module_names: ['化学反应原理'] },
    { subject_code: 'biology', subject_name: '生物', score: 59, full_score: 100, rate: 0.59, weak_module_names: ['遗传与进化'] }
  ],
  weak_points: [
    {
      id: 'wp_1',
      subject_code: 'math',
      subject_name: '数学',
      module: '函数与导数',
      chapter: '导数应用',
      knowledge_point: '利用导数求含参函数的单调区间与极值',
      severity: 'high',
      lost_score: 10,
      evidence: {
        question_no: '18(2)',
        question_text: '已知函数 f(x)=x³-3ax+1，讨论 f(x) 的单调性并求极值。',
        student_answer: '直接令 f′(x)=0 得 x=±√a，未讨论 a≤0 的情况，极值表填写有误。',
        correct_answer: '需分 a≤0 与 a>0 两种情况讨论，a>0 时 x=-√a 取极大值、x=√a 取极小值。',
        error_type: '分类讨论不完整'
      },
      suggestion: '把「含参函数单调性」的讨论模板背熟：先求定义域 → 求导 → 令导数为零解出临界点 → 按参数正负分类 → 列表判断符号。每天做 2 道同类题并对答案逐步核对讨论是否遗漏。'
    },
    {
      id: 'wp_2',
      subject_code: 'math',
      subject_name: '数学',
      module: '解析几何',
      chapter: '圆锥曲线',
      knowledge_point: '直线与椭圆联立后的韦达定理与弦长公式',
      severity: 'high',
      lost_score: 12,
      evidence: {
        question_no: '21',
        question_text: '椭圆 x²/4+y²=1 与直线 l 交于 A、B 两点，求 |AB| 的最大值。',
        student_answer: '联立后直接代入求根公式，计算量失控，未使用韦达定理化简。',
        correct_answer: '设 l:y=kx+m，联立得 (1+4k²)x²+8kmx+4m²-4=0，用韦达定理与弦长公式化简求最值。',
        error_type: '方法选择不当'
      },
      suggestion: '整理「圆锥曲线五步法」笔记：设线 → 联立 → 韦达 → 转化目标式 → 求最值。专门训练「设而不求」的意识，避免硬解方程。'
    },
    {
      id: 'wp_3',
      subject_code: 'physics',
      subject_name: '物理',
      module: '电磁学',
      chapter: '电磁感应',
      knowledge_point: '导体棒切割磁感线的能量转化与安培力做功',
      severity: 'high',
      lost_score: 8,
      evidence: {
        question_no: '15(2)',
        question_text: '导体棒在磁场中受恒力作用滑动，求最终速度与产生的焦耳热。',
        student_answer: '用动能定理时漏掉了安培力做功，只写了恒力做功。',
        correct_answer: '恒力做功 = 动能变化 + 焦耳热，需用能量守恒列式。',
        error_type: '模型理解偏差'
      },
      suggestion: '把「电磁感应四类模型」（单棒、双棒、线框、含容）各画一张受力与能量流向图，做题前先判断能量出口。'
    },
    {
      id: 'wp_4',
      subject_code: 'biology',
      subject_name: '生物',
      module: '遗传与进化',
      chapter: '孟德尔定律',
      knowledge_point: '两对相对性状的自由组合与配子概率计算',
      severity: 'medium',
      lost_score: 6,
      evidence: {
        question_no: '32(3)',
        question_text: 'AaBb × Aabb 后代中双显性个体的比例。',
        student_answer: '误算为 9/16，未按两对基因分别计算再相乘。',
        correct_answer: '3/4 × 1/2 = 3/8。',
        error_type: '计算失误'
      },
      suggestion: '强化「拆对法」：先把每对基因单独算比例，再相乘。每天 5 道快速计算题，限时 3 分钟。'
    },
    {
      id: 'wp_5',
      subject_code: 'chinese',
      subject_name: '语文',
      module: '古诗文',
      chapter: '文言文阅读',
      knowledge_point: '文言实词的一词多义与语境推断',
      severity: 'medium',
      lost_score: 5,
      evidence: {
        question_no: '13(1)',
        question_text: '翻译「因遗策，南取汉中」中的「因」。',
        student_answer: '译为「因为」，未结合语境判断为「沿袭」。',
        correct_answer: '沿袭、继承。',
        error_type: '实词积累不足'
      },
      suggestion: '建立 120 个高频文言实词卡片，每个词记录 2 个课内例句，用「代入语境读一遍」自检。'
    },
    {
      id: 'wp_6',
      subject_code: 'chinese',
      subject_name: '语文',
      module: '写作',
      chapter: '任务驱动型作文',
      knowledge_point: '材料的任务指令拆解与论证层次',
      severity: 'medium',
      lost_score: 7,
      evidence: {
        question_no: '作文',
        question_text: '根据材料写一篇不少于 800 字的文章。',
        student_answer: '中心论点明确但三段并列，缺少递进与辩证分析。',
        correct_answer: '应「是什么—为什么—怎么办」递进展开，并回应材料中的对立观点。',
        error_type: '结构扁平'
      },
      suggestion: '每周写 1 篇提纲训练：只写标题、中心论点、三个分论点及层次关系（并列/递进/对照），不写全文，20 分钟一篇。'
    },
    {
      id: 'wp_7',
      subject_code: 'english',
      subject_name: '英语',
      module: '写作',
      chapter: '读后续写',
      knowledge_point: '情节推进与情感线索的呼应',
      severity: 'low',
      lost_score: 4,
      evidence: {
        question_no: '写作',
        question_text: '读后续写两段，要求与原文情节衔接。',
        student_answer: '情节推进尚可，但缺少对人物心理的描写，句式单一。',
        correct_answer: '需在关键节点加入心理与动作描写，并适当使用非谓语与倒装句式。',
        error_type: '表达单一'
      },
      suggestion: '背诵 10 组「心理描写 + 动作描写」句块，续写时刻意在每段放入 1 处。'
    },
    {
      id: 'wp_8',
      subject_code: 'chemistry',
      subject_name: '化学',
      module: '化学反应原理',
      chapter: '化学平衡',
      knowledge_point: '平衡常数计算与转化率图像分析',
      severity: 'medium',
      lost_score: 6,
      evidence: {
        question_no: '17(2)',
        question_text: '根据图像判断温度对平衡的影响并计算 K。',
        student_answer: '图像趋势判断正确，但 K 的三段式列式时把初始量代错。',
        correct_answer: '用「三段式」列出起始、变化、平衡量后再代入 K 表达式。',
        error_type: '计算规范缺失'
      },
      suggestion: '所有平衡计算题强制写三段式表格，先列已知再看未知，养成不跳步的习惯。'
    }
  ],
  improvement_plan: {
    summary: '本次考试总分得分率 66.4%，相比上次提升 2.1 个百分点，主要拖累项是数学（58.7%）与生物（59.0%）。失分集中在「方法选择与分类讨论」和「模型理解」两类问题，属于可通过专项训练快速回收的分数，预计 3 周可提升 25~35 分。',
    priority_order: ['数学·导数与圆锥曲线（22 分）', '物理·电磁感应能量分析（8 分）', '生物·遗传概率计算（6 分）', '化学·平衡计算规范（6 分）', '语文·文言实词与作文结构（12 分）', '英语·读后续写描写（4 分）'],
    steps: [
      {
        title: '第 1 步：堵住数学的两个大出血点',
        detail: '导数含参讨论 + 圆锥曲线「设而不求」。先看课本例题重建模板，再做近 3 年真题中的同类题，每道题限时 12 分钟，做完必须自查「分类是否完整、是否用了韦达定理」。',
        estimated_hours: 8,
        resources: ['人教版选择性必修二 5.3 导数在研究函数中的应用', '近三年高考真题分类汇编·圆锥曲线']
      },
      {
        title: '第 2 步：物理电磁感应的能量视角',
        detail: '把安培力做功与焦耳热的对应关系用能量流向图固化，重做 10 道导体棒模型题，每题先写能量方程再动笔计算。',
        estimated_hours: 5,
        resources: ['物理必修三 电磁感应章节', '能量守恒专题讲义']
      },
      {
        title: '第 3 步：化生两科的规范与计算',
        detail: '化学所有平衡题强制写三段式；生物遗传题用「拆对法」限时训练，每天 5 题，3 分钟内完成。',
        estimated_hours: 4,
        resources: ['化学反应原理 第二章', '遗传学专题练习册']
      },
      {
        title: '第 4 步：语文英语的积累型补救',
        detail: '每天 10 个文言实词卡片 + 每周 1 篇作文提纲训练；读后续写每段强制加入 1 处心理描写。',
        estimated_hours: 6,
        resources: ['高考文言实词 120 例', '读后续写句块手册']
      }
    ],
    weekly_plan: [
      { week: 1, focus: '数学导数与圆锥曲线模板重建', target: '同类题正确率从 45% 提升到 70%' },
      { week: 2, focus: '物理电磁感应 + 化学平衡规范', target: '两科各完成 10 道专项题，错题率低于 30%' },
      { week: 3, focus: '生物遗传计算 + 语英积累', target: '遗传计算题限时正确率 85%，文言实词掌握 60 个' },
      { week: 4, focus: '综合限时套卷 + 错题回炉', target: '总分得分率提升到 70% 以上' }
    ]
  },
  model_name: 'qwen-vl-max（演示数据）',
  created_at: '2026-09-10T21:30:00+08:00'
}

export const MOCK_CREDITS_LEDGER: CreditLedgerItem[] = [
  { id: 'cl_1', delta: 1, reason: 'register_bonus', balance_after: 1, remark: '新用户注册赠送', created_at: '2026-08-20T09:00:00+08:00' },
  { id: 'cl_2', delta: -1, reason: 'analysis', balance_after: 0, remark: '分析：高二下·期中考', created_at: '2026-09-10T21:30:00+08:00' },
  { id: 'cl_3', delta: 1, reason: 'ad_reward', balance_after: 1, remark: '观看激励视频累计 10 次', created_at: '2026-09-10T22:05:00+08:00' },
  { id: 'cl_4', delta: 1, reason: 'ad_reward', balance_after: 2, remark: '观看激励视频累计 10 次', created_at: '2026-09-11T12:40:00+08:00' }
]

export const MOCK_AD_PROGRESS: AdProgress = { watched: 3, needed: 10, per_reward: 1, credits: 2 }

/**
 * 充值套餐。
 * 定价策略：Android / 鸿蒙 ¥1.00 每点数；iOS 走 App Store 内购 ¥1.50 每点数，
 * 用以覆盖苹果平台服务费（中国区标准佣金率见 docs/04-支付与变现.md）。
 */
export const MOCK_PLANS: Plan[] = [
  { id: 'plan_1', name: '体验包', credits: 1, price_cents: 100, currency: 'CNY', channel: 'wechat' },
  { id: 'plan_5', name: '标准包', credits: 5, price_cents: 500, currency: 'CNY', channel: 'wechat', recommended: true },
  { id: 'plan_20', name: '强化包', credits: 20, price_cents: 2000, currency: 'CNY', channel: 'wechat' },
  { id: 'plan_ios_1', name: '体验包', credits: 1, price_cents: 150, currency: 'CNY', channel: 'apple_iap' },
  { id: 'plan_ios_5', name: '标准包', credits: 5, price_cents: 750, currency: 'CNY', channel: 'apple_iap', recommended: true },
  { id: 'plan_ios_20', name: '强化包', credits: 20, price_cents: 3000, currency: 'CNY', channel: 'apple_iap' }
]

/** 模拟「AI 正在分析」的耗时任务 */
export function mockTaskDelay(ms = 1500): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
