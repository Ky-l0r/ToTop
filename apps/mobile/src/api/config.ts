/**
 * 前端运行配置。
 *
 * USE_MOCK = true  ：演示模式。所有接口直接返回 src/mock 里的结构化假数据，
 *                    不需要后端即可完整体验三个页面（默认开启，便于先看效果）。
 * USE_MOCK = false ：真实模式。请求会打到 API_BASE_URL，失败时若该接口定义了
 *                    fallback 仍会自动降级到假数据并在界面标注「演示数据」。
 *
 * 切换到真实后端：把 USE_MOCK 改成 false，并把 API_BASE_URL 改成本机/服务器地址。
 * - H5 开发：留空字符串即可，由 vite.config.ts 的 proxy 转发到 http://127.0.0.1:8000
 * - App / 鸿蒙真机：必须写局域网 IP 或公网域名，例如 http://192.168.1.10:8000
 */
export const USE_MOCK = true

/**
 * 后端地址。
 * 只声明一次变量、用条件编译改值，避免出现两个同名 export（那会让 tsc 报重复声明）。
 */
let apiBaseUrl = 'http://192.168.1.10:8000'

// #ifdef H5
// H5 端留空，由 vite.config.ts 的 proxy 转发到 http://127.0.0.1:8000
apiBaseUrl = ''
// #endif

export const API_BASE_URL = apiBaseUrl

export const API_PREFIX = '/api/v1'

/** 单次 AI 分析消耗的点数 */
export const ANALYSIS_COST = 1

/** 看多少次激励视频广告换 1 点数 */
export const ADS_PER_CREDIT = 10

/** 请求超时（毫秒）。AI 分析是异步任务，普通接口 30 秒足够 */
export const REQUEST_TIMEOUT = 30000
