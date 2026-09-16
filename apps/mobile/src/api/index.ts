/** 所有接口调用集中在这里，页面只依赖本文件，不直接碰 request。 */
import { request, uploadFile } from './request'
import { ANALYSIS_COST } from './config'
import {
  MOCK_AD_PROGRESS,
  MOCK_ANALYSIS,
  MOCK_CREDITS_LEDGER,
  MOCK_EXAMS,
  MOCK_PLANS,
  MOCK_SUBJECTS,
  MOCK_TREND,
  MOCK_USER,
  mockTaskDelay
} from '@/mock'
import type {
  AdProgress,
  Analysis,
  AnswerSheet,
  AuthResult,
  CreditLedgerItem,
  ExamDetail,
  ExamSummary,
  ImportTask,
  Paged,
  Paper,
  Plan,
  SourceType,
  SubjectMeta,
  TrendData,
  User
} from '@/types'

/* ------------------------------------------------------------------ meta */

export function fetchSubjects(): Promise<SubjectMeta[]> {
  return request<SubjectMeta[]>({ url: '/meta/subjects', fallback: () => MOCK_SUBJECTS })
}

/* ------------------------------------------------------------------ auth */

export function sendSmsCode(phone: string, scene: 'register' | 'login' | 'reset'): Promise<{ sent: boolean; dev_code?: string }> {
  return request({
    url: '/auth/sms/send',
    method: 'POST',
    data: { phone, scene },
    fallback: () => ({ sent: true, dev_code: '123456' })
  })
}

export function register(phone: string, smsCode: string, password?: string): Promise<AuthResult> {
  return request<AuthResult>({
    url: '/auth/register',
    method: 'POST',
    data: { phone, sms_code: smsCode, password },
    fallback: () => ({
      access_token: 'mock_access_token',
      refresh_token: 'mock_refresh_token',
      user: { ...MOCK_USER, phone: maskPhone(phone), credits: 1, created_at: new Date().toISOString() }
    })
  })
}

export function loginByPassword(phone: string, password: string): Promise<AuthResult> {
  return request<AuthResult>({
    url: '/auth/login/password',
    method: 'POST',
    data: { phone, password },
    fallback: () => ({
      access_token: 'mock_access_token',
      refresh_token: 'mock_refresh_token',
      user: { ...MOCK_USER, phone: maskPhone(phone) }
    })
  })
}

export function loginBySms(phone: string, smsCode: string): Promise<AuthResult> {
  return request<AuthResult>({
    url: '/auth/login/sms',
    method: 'POST',
    data: { phone, sms_code: smsCode },
    fallback: () => ({
      access_token: 'mock_access_token',
      refresh_token: 'mock_refresh_token',
      user: { ...MOCK_USER, phone: maskPhone(phone) }
    })
  })
}

export function setPassword(oldPassword: string | undefined, newPassword: string): Promise<{ ok: boolean }> {
  return request({
    url: '/auth/password',
    method: 'POST',
    auth: true,
    data: { old_password: oldPassword, new_password: newPassword },
    fallback: () => ({ ok: true })
  })
}

/* ------------------------------------------------------------------ user */

export function fetchMe(): Promise<User> {
  return request<User>({ url: '/users/me', auth: true, fallback: () => MOCK_USER })
}

export function updateMe(payload: { nickname?: string; avatar_url?: string }): Promise<User> {
  return request<User>({
    url: '/users/me',
    method: 'PATCH',
    auth: true,
    data: payload,
    fallback: () => ({ ...MOCK_USER, ...payload })
  })
}

/* --------------------------------------------------------------- credits */

export function fetchBalance(): Promise<{ credits: number }> {
  return request({ url: '/credits/balance', auth: true, fallback: () => ({ credits: MOCK_USER.credits }) })
}

export function fetchLedger(page = 1, size = 20): Promise<Paged<CreditLedgerItem>> {
  return request<Paged<CreditLedgerItem>>({
    url: `/credits/ledger?page=${page}&size=${size}`,
    auth: true,
    fallback: () => ({ items: MOCK_CREDITS_LEDGER, total: MOCK_CREDITS_LEDGER.length })
  })
}

export function fetchAdProgress(): Promise<AdProgress> {
  return request<AdProgress>({ url: '/credits/ad-progress', auth: true, fallback: () => MOCK_AD_PROGRESS })
}

export function reportAdReward(platform: string, eventId: string, watchedSeconds: number): Promise<{
  credited: boolean
  credits: number
  progress: { watched: number; needed: number }
}> {
  return request({
    url: '/credits/ad-reward',
    method: 'POST',
    auth: true,
    data: { platform, event_id: eventId, watched_seconds: watchedSeconds },
    fallback: () => {
      const watched = MOCK_AD_PROGRESS.watched + 1
      const credited = watched >= MOCK_AD_PROGRESS.needed
      return {
        credited,
        credits: MOCK_USER.credits + (credited ? 1 : 0),
        progress: { watched: credited ? 0 : watched, needed: MOCK_AD_PROGRESS.needed }
      }
    }
  })
}

/* ---------------------------------------------------------------- orders */

export function fetchPlans(): Promise<Plan[]> {
  return request<Plan[]>({ url: '/orders/plans', auth: true, fallback: () => MOCK_PLANS })
}

export function createOrder(planId: string, channel: Plan['channel']): Promise<{
  order_id: string
  order_no: string
  amount_cents: number
  pay_params: Record<string, any>
}> {
  return request({
    url: '/orders',
    method: 'POST',
    auth: true,
    data: { plan_id: planId, channel },
    fallback: () => ({
      order_id: `ord_${Date.now()}`,
      order_no: `TT${Date.now()}`,
      amount_cents: MOCK_PLANS.find((p) => p.id === planId)?.price_cents ?? 100,
      pay_params: { mock: true }
    })
  })
}

/* ----------------------------------------------------------------- exams */

export function fetchExams(page = 1, size = 20): Promise<Paged<ExamSummary>> {
  return request<Paged<ExamSummary>>({
    url: `/exams?page=${page}&size=${size}`,
    auth: true,
    fallback: () => ({ items: MOCK_EXAMS, total: MOCK_EXAMS.length })
  })
}

export function createExam(payload: {
  name: string
  exam_date: string
  grade: string
  exam_type?: string
  total_full_score?: number
}): Promise<ExamSummary> {
  return request<ExamSummary>({
    url: '/exams',
    method: 'POST',
    auth: true,
    data: payload,
    fallback: () => ({
      id: `exam_${Date.now()}`,
      name: payload.name,
      exam_date: payload.exam_date,
      grade: payload.grade,
      total_full_score: payload.total_full_score ?? 750,
      subject_count: 0,
      analysis_status: 'none',
      created_at: new Date().toISOString()
    })
  })
}

export function fetchExamDetail(id: string): Promise<ExamDetail> {
  return request<ExamDetail>({
    url: `/exams/${id}`,
    auth: true,
    fallback: () => ({
      ...(MOCK_EXAMS.find((e) => e.id === id) ?? MOCK_EXAMS[0]),
      papers: [],
      answer_sheets: []
    })
  })
}

export function deleteExam(id: string): Promise<{ ok: boolean }> {
  return request({ url: `/exams/${id}`, method: 'DELETE', auth: true, fallback: () => ({ ok: true }) })
}

/* --------------------------------------------------------------- uploads */

export { uploadFile }

/** 把拍好/选好的文件登记成一份「题目/试卷」 */
export function createPaper(examId: string, payload: {
  subject_code: string
  title: string
  source_type: SourceType
  object_keys: string[]
}): Promise<{ paper_id: string; task_id: string }> {
  return request({
    url: `/exams/${examId}/papers`,
    method: 'POST',
    auth: true,
    data: payload,
    fallback: () => ({ paper_id: `paper_${Date.now()}`, task_id: `task_${Date.now()}` })
  })
}

/** 把拍好/选好的文件登记成一份「答题卡」 */
export function createAnswerSheet(examId: string, payload: {
  subject_code: string
  paper_id?: string
  source_type: SourceType
  is_graded: boolean
  object_keys: string[]
  score?: number
  full_score?: number
}): Promise<{ answer_sheet_id: string; task_id: string }> {
  return request({
    url: `/exams/${examId}/answer-sheets`,
    method: 'POST',
    auth: true,
    data: payload,
    fallback: () => ({ answer_sheet_id: `sheet_${Date.now()}`, task_id: `task_${Date.now()}` })
  })
}

export function fetchTask(taskId: string): Promise<ImportTask> {
  return request<ImportTask>({
    url: `/tasks/${taskId}`,
    auth: true,
    fallback: async () => {
      await mockTaskDelay(800)
      return { id: taskId, status: 'succeeded', progress: 100, message: '识别完成' }
    }
  })
}

/* -------------------------------------------------------------- analyses */

export function createAnalysis(examId: string, answerSheetId?: string, force = false): Promise<{
  analysis_id: string
  status: string
  credits_cost: number
  credits_balance: number
}> {
  return request({
    url: '/analyses',
    method: 'POST',
    auth: true,
    data: { exam_id: examId, answer_sheet_id: answerSheetId, force },
    fallback: () => ({
      analysis_id: MOCK_ANALYSIS.id,
      status: 'pending',
      credits_cost: ANALYSIS_COST,
      credits_balance: Math.max(0, MOCK_USER.credits - ANALYSIS_COST)
    })
  })
}

export function fetchAnalysis(id: string): Promise<Analysis> {
  return request<Analysis>({ url: `/analyses/${id}`, auth: true, fallback: () => MOCK_ANALYSIS })
}

export function fetchAnalyses(examId?: string, page = 1, size = 10): Promise<Paged<Analysis>> {
  return request<Paged<Analysis>>({
    url: `/analyses?page=${page}&size=${size}${examId ? `&exam_id=${examId}` : ''}`,
    auth: true,
    fallback: () => ({ items: [MOCK_ANALYSIS], total: 1 })
  })
}

/* ---------------------------------------------------------------- trends */

export function fetchTrends(limit = 10, subjects?: string[]): Promise<TrendData> {
  const query = [`limit=${limit}`]
  if (subjects && subjects.length) query.push(`subjects=${subjects.join(',')}`)
  return request<TrendData>({
    url: `/trends?${query.join('&')}`,
    auth: true,
    fallback: () => MOCK_TREND
  })
}

/* ----------------------------------------------------------------- utils */

function maskPhone(phone: string): string {
  if (!phone || phone.length < 7) return phone
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}
