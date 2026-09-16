/** 全局类型定义，与后端 /api/v1 契约一一对应（见 docs/02-API契约.md）。 */

/** 后端统一响应体 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/** 分页结构 */
export interface Paged<T> {
  items: T[]
  total: number
}

export interface User {
  id: string
  /** 已脱敏，如 138****8888 */
  phone: string
  nickname: string
  avatar_url: string
  /** 点数余额 */
  credits: number
  invite_code: string
  created_at: string
}

export interface AuthResult {
  access_token: string
  refresh_token: string
  user: User
}

export interface SubjectMeta {
  code: string
  name: string
  modules?: { name: string; chapters: string[] }[]
}

/** 趋势图上的一个考试节点 */
export interface TrendPoint {
  exam_id: string
  exam_name: string
  exam_date: string
  /** 总分得分率 0~1 */
  total_rate: number
  /** 学科 code -> 得分率 0~1 */
  subjects: Record<string, number>
}

export interface TrendData {
  points: TrendPoint[]
  subjects: { code: string; name: string }[]
}

export type AnalysisStatus = 'pending' | 'running' | 'succeeded' | 'failed'
export type TaskStatus = AnalysisStatus

export interface ExamSummary {
  id: string
  name: string
  exam_date: string
  grade: string
  total_full_score: number
  subject_count: number
  analysis_status: AnalysisStatus | 'none'
  created_at: string
}

export interface Paper {
  id: string
  exam_id: string
  subject_code: string
  title: string
  source_type: SourceType
  object_keys: string[]
  ocr_status: TaskStatus
  ocr_text?: string
  question_count?: number
  created_at: string
}

export interface AnswerSheet {
  id: string
  exam_id: string
  paper_id?: string
  subject_code: string
  source_type: SourceType
  /** 是否已由老师批改（true 时 AI 只做归因而非判分） */
  is_graded: boolean
  object_keys: string[]
  ocr_status: TaskStatus
  score?: number
  full_score?: number
  created_at: string
}

export interface ExamDetail extends ExamSummary {
  note?: string
  papers: Paper[]
  answer_sheets: AnswerSheet[]
}

export type SourceType = 'photo' | 'pdf' | 'docx' | 'image'

/** 失分知识点 */
export interface WeakPoint {
  id: string
  subject_code: string
  subject_name: string
  module: string
  chapter: string
  knowledge_point: string
  severity: 'high' | 'medium' | 'low'
  lost_score: number
  evidence: {
    question_no: string
    question_text: string
    student_answer: string
    correct_answer: string
    error_type: string
  }
  suggestion: string
}

export interface SubjectScore {
  subject_code: string
  subject_name: string
  score: number
  full_score: number
  rate: number
  weak_module_names: string[]
}

export interface ImprovementStep {
  title: string
  detail: string
  estimated_hours: number
  resources: string[]
}

export interface ImprovementPlan {
  summary: string
  priority_order: string[]
  steps: ImprovementStep[]
  weekly_plan: { week: number; focus: string; target: string }[]
}

export interface Analysis {
  id: string
  exam_id: string
  status: AnalysisStatus
  total_score: number
  full_score: number
  score_rate: number
  subjects: SubjectScore[]
  weak_points: WeakPoint[]
  improvement_plan: ImprovementPlan
  model_name: string
  created_at: string
}

export interface CreditLedgerItem {
  id: string
  delta: number
  reason: 'register_bonus' | 'analysis' | 'ad_reward' | 'purchase' | 'admin_adjust' | 'refund'
  balance_after: number
  remark: string
  created_at: string
}

export interface AdProgress {
  /** 已观看广告次数（当前累计周期内） */
  watched: number
  /** 需要多少次才能换 1 点数 */
  needed: number
  per_reward: number
  credits: number
}

export interface Plan {
  id: string
  name: string
  credits: number
  price_cents: number
  currency: string
  channel: 'wechat' | 'alipay' | 'apple_iap' | 'mock'
  recommended?: boolean
}

export interface ImportTask {
  id: string
  status: TaskStatus
  progress: number
  message: string
  error?: string
  result_ref?: string
}
