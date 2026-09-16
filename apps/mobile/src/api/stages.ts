/** 分阶段解锁相关接口。 */
import { request } from './request'
import { MOCK_ANALYSIS } from '@/mock'
import { mockReportAd, mockStages, mockUnlockByCredit } from '@/mock/stage'
import type { AnalysisStage, StageNo, StagedAnalysis, UnlockMethod } from '@/types/stage'

export interface UnlockStageResult {
  stage: StageNo
  watched: number
  ad_required: number
  unlocked: boolean
  credited: boolean
  duplicated: boolean
  unlock_method: UnlockMethod | null
  content: unknown
  credits_balance?: number
}

/** 拉取带分阶段信息的分析结果 */
export function fetchStagedAnalysis(analysisId: string): Promise<StagedAnalysis> {
  return request<StagedAnalysis>({
    url: `/analyses/${analysisId}`,
    auth: true,
    fallback: () => ({ ...MOCK_ANALYSIS, stages: mockStages(analysisId) }) as StagedAnalysis
  })
}

/** 上报一次激励视频观看，累计到次数即解锁该阶段（服务端按 event_id 幂等） */
export function reportStageAd(
  analysisId: string,
  stage: StageNo,
  eventId: string,
  watchedSeconds = 30
): Promise<UnlockStageResult> {
  const platform = detectAdPlatform()
  return request<UnlockStageResult>({
    url: `/analyses/${analysisId}/stages/${stage}/unlock`,
    method: 'POST',
    auth: true,
    data: { platform, event_id: eventId, watched_seconds: watchedSeconds },
    fallback: () => mockReportAd(analysisId, stage, eventId) as UnlockStageResult
  })
}

/** 用点数跳过广告，直接解锁该阶段 */
export function unlockStageByCredit(analysisId: string, stage: StageNo): Promise<UnlockStageResult> {
  return request<UnlockStageResult>({
    url: `/analyses/${analysisId}/stages/${stage}/unlock-by-credit`,
    method: 'POST',
    auth: true,
    fallback: () => mockUnlockByCredit(analysisId, stage) as UnlockStageResult
  })
}

/** 当前平台的广告来源标识，用于服务端统计与风控 */
function detectAdPlatform(): string {
  // #ifdef MP-WEIXIN
  return 'wechat'
  // #endif
  // #ifndef MP-WEIXIN
  return 'mock'
  // #endif
}

/** 生成一次广告上报的事件 ID（服务端据此去重） */
export function newAdEventId(analysisId: string, stage: StageNo): string {
  return `ad_${analysisId}_${stage}_${Date.now()}_${Math.floor(Math.random() * 1e6)}`
}

/** 判断某阶段是否还需要看广告 */
export function remainingAdCount(stage: AnalysisStage): number {
  return Math.max(0, stage.ad_required - stage.watched)
}
