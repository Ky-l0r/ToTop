import { API_BASE_URL, API_PREFIX, REQUEST_TIMEOUT, USE_MOCK } from './config'
import type { ApiResponse } from '@/types'

const ACCESS_TOKEN_KEY = 'tt_access_token'
const REFRESH_TOKEN_KEY = 'tt_refresh_token'

export function getToken(): string {
  try {
    return uni.getStorageSync(ACCESS_TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

export function getRefreshToken(): string {
  try {
    return uni.getStorageSync(REFRESH_TOKEN_KEY) || ''
  } catch (e) {
    return ''
  }
}

export function setTokens(accessToken: string, refreshToken?: string): void {
  uni.setStorageSync(ACCESS_TOKEN_KEY, accessToken)
  if (refreshToken) uni.setStorageSync(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearTokens(): void {
  uni.removeStorageSync(ACCESS_TOKEN_KEY)
  uni.removeStorageSync(REFRESH_TOKEN_KEY)
}

/** 业务错误：code 对应后端约定的错误码（401xx 鉴权 / 402xx 点数不足 等） */
export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

interface RequestOptions<T> {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  data?: Record<string, any>
  auth?: boolean
  /** 真实请求失败时使用的降级数据（演示模式直接走这里） */
  fallback?: () => T | Promise<T>
  /** 演示模式下的模拟耗时（毫秒），让加载动画可见 */
  mockDelay?: number
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 未登录/登录过期时统一跳转到账号页 */
function redirectToLogin(): void {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  const current = pages.length ? pages[pages.length - 1].route : ''
  if (current === 'pages/account/index') return
  uni.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
  setTimeout(() => uni.switchTab({ url: '/pages/account/index' }), 600)
}

/**
 * 统一请求入口。
 * 演示模式（USE_MOCK）下不发网络请求，直接返回 fallback 数据；
 * 真实模式下失败且提供 fallback 时自动降级，保证界面始终有内容。
 */
export async function request<T>(options: RequestOptions<T>): Promise<T> {
  const { url, method = 'GET', data, auth = false, fallback, mockDelay = 240 } = options

  if (USE_MOCK) {
    if (fallback) {
      await delay(mockDelay)
      return fallback()
    }
    throw new ApiError(50001, `演示模式未提供 ${url} 的假数据`)
  }

  try {
    const result = await rawRequest<T>({ url, method, data, auth })
    return result
  } catch (error) {
    if (fallback) {
      console.warn(`[api] ${method} ${url} 失败，已降级为演示数据：`, error)
      return fallback()
    }
    throw error
  }
}

function rawRequest<T>(options: {
  url: string
  method: string
  data?: Record<string, any>
  auth?: boolean
}): Promise<T> {
  const { url, method, data, auth } = options
  return new Promise<T>((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' }
    if (auth) {
      const token = getToken()
      if (!token) {
        redirectToLogin()
        reject(new ApiError(40101, '未登录'))
        return
      }
      header.Authorization = `Bearer ${token}`
    }

    uni.request({
      url: API_BASE_URL + API_PREFIX + url,
      method: method as any,
      data,
      header,
      timeout: REQUEST_TIMEOUT,
      success: (res) => {
        const status = res.statusCode
        const body = res.data as ApiResponse<T> | undefined
        if (status === 401) {
          clearTokens()
          redirectToLogin()
          reject(new ApiError(40101, '登录已过期'))
          return
        }
        if (status < 200 || status >= 300) {
          reject(new ApiError(status, (body && body.message) || `请求失败(${status})`))
          return
        }
        if (body && typeof body.code === 'number') {
          if (body.code === 0) resolve(body.data)
          else reject(new ApiError(body.code, body.message || '请求失败'))
          return
        }
        resolve(body as unknown as T)
      },
      fail: (err) => {
        reject(new ApiError(50000, err.errMsg || '网络异常，请检查网络连接'))
      }
    })
  })
}

/** 上传单个文件，返回后端存储的 object_key 与可访问 URL */
export function uploadFile(filePath: string, kind: 'paper' | 'answer_sheet'): Promise<{
  object_key: string
  url: string
  size: number
  content_type: string
}> {
  if (USE_MOCK) {
    return delay(600).then(() => ({
      object_key: `mock/${kind}/${Date.now()}-${Math.floor(Math.random() * 1e5)}.jpg`,
      url: filePath,
      size: 1024 * 512,
      content_type: 'image/jpeg'
    }))
  }

  return new Promise((resolve, reject) => {
    const token = getToken()
    uni.uploadFile({
      url: `${API_BASE_URL}${API_PREFIX}/uploads/direct`,
      filePath,
      name: 'file',
      formData: { kind },
      header: token ? { Authorization: `Bearer ${token}` } : {},
      timeout: 120000,
      success: (res) => {
        try {
          const body = JSON.parse(res.data) as ApiResponse<any>
          if (body.code === 0) resolve(body.data)
          else reject(new ApiError(body.code, body.message))
        } catch (e) {
          reject(new ApiError(50000, '上传响应解析失败'))
        }
      },
      fail: (err) => reject(new ApiError(50000, err.errMsg || '上传失败'))
    })
  })
}
