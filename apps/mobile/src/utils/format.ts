/** 通用格式化工具 */

/** 0.687 -> "68.7%" */
export function percent(rate: number | null | undefined, digits = 1): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '--'
  return `${(rate * 100).toFixed(digits)}%`
}

/** 0.687 -> "+2.1pt"（用于环比展示，传入的是百分点差值） */
export function deltaPt(delta: number | null | undefined, digits = 1): string {
  if (delta === null || delta === undefined || Number.isNaN(delta)) return '--'
  const sign = delta > 0 ? '+' : ''
  return `${sign}${delta.toFixed(digits)}pt`
}

/** 分 -> "¥5.00" */
export function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`
}

/** "2026-09-10T21:30:00+08:00" -> "2026-09-10" */
export function dateOnly(value: string): string {
  if (!value) return ''
  return value.slice(0, 10)
}

/** "2026-09-10T21:30:00+08:00" -> "09-10 21:30" */
export function dateTimeShort(value: string): string {
  if (!value) return ''
  const date = value.slice(0, 10)
  const time = value.slice(11, 16)
  return `${date.slice(5)} ${time}`
}

/** 考试名太长时截断：用于 X 轴标签 */
export function shortExamName(name: string): string {
  if (!name) return ''
  const parts = name.split(/[·・]/)
  const tail = parts.length > 1 ? parts[parts.length - 1] : name
  return tail.length > 5 ? `${tail.slice(0, 5)}…` : tail
}

/** 学科 code -> 主题色（与 uni.scss 中的变量保持一致） */
export const SUBJECT_COLORS: Record<string, string> = {
  chinese: '#ef4444',
  math: '#4c6fff',
  english: '#22c55e',
  physics: '#8b5cf6',
  chemistry: '#f59e0b',
  biology: '#14b8a6',
  politics: '#ec4899',
  history: '#a16207',
  geography: '#0ea5e9',
  total: '#1b2138'
}

export function subjectColor(code: string): string {
  return SUBJECT_COLORS[code] || '#94a3b8'
}

/** 分数等级文案 */
export function rateLevel(rate: number): { text: string; color: string } {
  if (rate >= 0.85) return { text: '优秀', color: '#22c55e' }
  if (rate >= 0.7) return { text: '良好', color: '#4c6fff' }
  if (rate >= 0.6) return { text: '及格', color: '#f59e0b' }
  return { text: '待提升', color: '#ef4444' }
}
