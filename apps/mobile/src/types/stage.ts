/** 分阶段解锁相关类型（对应后端 /analyses/{id} 的 stages 字段）。 */
import type { Analysis, ImprovementPlan } from './index'

export type StageNo = 1 | 2 | 3
export type UnlockMethod = 'ad' | 'credit'

/** 阶段 1「基础分析」：200 字以内的学情状况 */
export interface StageSummaryContent {
  summary: string
  score_rate: number
  total_score: number
  full_score: number
  level_text: string
  top_subjects: { name: string; rate: number }[]
}

/** 阶段 2「进阶分析」里的单道失分题 */
export interface WeakQuestion {
  question_no: string
  subject_name: string
  module: string
  knowledge_point: string
  lost_score: number
  severity: 'high' | 'medium' | 'low'
  error_type: string
  brief: string
}

export interface StageQuestionsContent {
  weak_questions: WeakQuestion[]
  total_lost: number
}

/** 阶段 3「归纳总结」里需要重点补的知识模块 */
export interface StageModule {
  module: string
  subject_name: string
  lost_score_total: number
  priority: number
  reason: string
}

export interface StagePlanContent {
  modules: StageModule[]
  suggestions: string[]
  improvement_plan: ImprovementPlan
}

export type StageContent = StageSummaryContent | StageQuestionsContent | StagePlanContent

export interface AnalysisStage {
  stage: StageNo
  name: string
  /** 解锁该阶段需要看几次广告 */
  ad_required: number
  /** 当前已累计的有效观看次数 */
  watched: number
  unlocked: boolean
  unlock_method: UnlockMethod | null
  /** 未解锁时为 null —— 内容不下发到客户端 */
  content: StageContent | null
}

/** 带分阶段信息的分析结果 */
export interface StagedAnalysis extends Analysis {
  stages: AnalysisStage[]
}

/** 阶段元信息（后端会下发，这里作为前端兜底与文案来源，保持与 stage_ad_requirements 一致） */
export const STAGE_META: { stage: StageNo; name: string; adRequired: number; desc: string; icon: string }[] = [
  {
    stage: 1,
    name: '基础分析',
    adRequired: 1,
    desc: '一句话看懂这次考试的整体表现',
    icon: '①'
  },
  {
    stage: 2,
    name: '进阶分析',
    adRequired: 2,
    desc: '定位失分最严重的题目与错因',
    icon: '②'
  },
  {
    stage: 3,
    name: '归纳总结',
    adRequired: 2,
    desc: '该重点补哪些知识模块、怎么补',
    icon: '③'
  }
]

/** 安全地取阶段内容（按 stage 收窄类型） */
export function asSummary(content: StageContent | null): StageSummaryContent | null {
  if (!content || !('summary' in content)) return null
  return content
}

export function asQuestions(content: StageContent | null): StageQuestionsContent | null {
  if (!content || !('weak_questions' in content)) return null
  return content
}

export function asPlan(content: StageContent | null): StagePlanContent | null {
  if (!content || !('modules' in content)) return null
  return content
}
