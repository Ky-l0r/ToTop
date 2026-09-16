/**
 * 激励视频广告封装（微信小程序）。
 *
 * 设计要点：
 * 1. 同一个 adUnitId 在小程序里是全局单例，这里只创建一次并常驻预加载，避免重复创建导致拉取失败。
 * 2. `show()` 失败时先 `load()` 再重试一次——这是微信官方推荐的容错写法。
 * 3. `onClose` 必须用同一个函数引用注册/注销，否则会重复触发奖励发放。
 * 4. 未配置广告位（开发阶段）时走演示流程，保证整套解锁逻辑可自测。
 */
import { USE_MOCK } from '@/api/config'

/**
 * 微信小程序激励视频广告位 ID。
 * 获取路径：微信公众平台 → 流量主 → 广告位管理 → 新建「激励视频」广告位。
 * 留空时使用演示流程（不产生真实广告请求）。
 */
export const REWARDED_AD_UNIT_ID = ''

export interface AdPlayResult {
  /** 是否完整观看（可发放奖励） */
  ok: boolean
  reason?: string
  /** 是否为演示模式（未配置真实广告位） */
  demo: boolean
}

let adInstance: any = null
let adFailed = false

function hasRealAd(): boolean {
  return !!REWARDED_AD_UNIT_ID && typeof uni.createRewardedVideoAd === 'function'
}

/** 在 App 启动或进入解锁页时调用，提前拉取广告，提升点击后的展示成功率 */
export function initRewardedAd(): void {
  if (!hasRealAd() || adInstance || adFailed) return
  try {
    adInstance = uni.createRewardedVideoAd({ adUnitId: REWARDED_AD_UNIT_ID })
    adInstance.onError((err: any) => {
      console.error('[ad] 激励视频异常', err)
      adFailed = true
    })
    adInstance.load().catch(() => {
      /* 首次预加载失败不阻塞，用户点击时会重试 */
    })
  } catch (e) {
    console.error('[ad] 创建广告实例失败', e)
    adFailed = true
  }
}

/** 播放一次激励视频；resolve 的 ok 表示是否完整观看 */
export function playRewardedAd(): Promise<AdPlayResult> {
  // 演示模式：模拟一次 1.2 秒的广告播放
  if (USE_MOCK || !hasRealAd()) {
    return new Promise((resolve) => {
      uni.showLoading({ title: '广告播放中…', mask: true })
      setTimeout(() => {
        uni.hideLoading()
        resolve({ ok: true, demo: true })
      }, 1200)
    })
  }

  initRewardedAd()
  if (!adInstance) {
    return Promise.resolve({ ok: false, reason: '当前环境不支持激励视频', demo: false })
  }

  return new Promise<AdPlayResult>((resolve) => {
    let settled = false

    const finish = (result: AdPlayResult) => {
      if (settled) return
      settled = true
      adInstance.offClose(onClose)
      resolve(result)
    }

    const onClose = (res: any) => {
      const ended = !!(res && res.isEnded)
      finish(
        ended
          ? { ok: true, demo: false }
          : { ok: false, reason: '需要完整看完广告才能解锁', demo: false }
      )
    }

    adInstance.onClose(onClose)

    adInstance.show().catch(() => {
      adInstance
        .load()
        .then(() => adInstance.show())
        .catch(() => finish({ ok: false, reason: '广告加载失败，请稍后重试', demo: false }))
    })
  })
}

/**
 * 连续播放 n 次广告。
 * 中途用户关闭（未看完）即停止，返回实际完成次数。
 */
export async function playRewardedAdTimes(
  times: number,
  onEach?: (done: number, total: number) => void
): Promise<{ done: number; result: AdPlayResult }> {
  let done = 0
  let last: AdPlayResult = { ok: true, demo: USE_MOCK }
  for (let i = 0; i < times; i++) {
    last = await playRewardedAd()
    if (!last.ok) return { done, result: last }
    done += 1
    if (onEach) onEach(done, times)
  }
  return { done, result: last }
}
