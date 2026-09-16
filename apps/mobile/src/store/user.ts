import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '@/api'
import { loginByWechat as apiLoginByWechat } from '@/api/wechat'
import { clearTokens, getToken, setTokens } from '@/api/request'
import type { AuthResult, User } from '@/types'

/**
 * 用户状态：登录态、点数余额、广告进度。
 * token 存在本地 storage，小程序冷启动时用 bootstrap() 静默恢复登录。
 */
export const useUserStore = defineStore('user', () => {
  const user = ref<User | null>(null)
  const loading = ref(false)
  const adProgress = ref({ watched: 0, needed: 10 })

  const isLogin = computed(() => !!user.value)
  const credits = computed(() => user.value?.credits ?? 0)
  const nickname = computed(() => user.value?.nickname || '未登录')
  const phoneMasked = computed(() => user.value?.phone || '')

  function applyAuth(result: AuthResult): void {
    setTokens(result.access_token, result.refresh_token)
    user.value = result.user
  }

  /** 冷启动恢复登录态；没有 token 就当作未登录 */
  async function bootstrap(): Promise<void> {
    if (!getToken()) return
    try {
      user.value = await api.fetchMe()
      await refreshAdProgress()
    } catch (e) {
      console.warn('[user] 恢复登录态失败', e)
    }
  }

  async function loginByPassword(phone: string, password: string): Promise<void> {
    loading.value = true
    try {
      applyAuth(await api.loginByPassword(phone, password))
      await refreshAdProgress()
    } finally {
      loading.value = false
    }
  }

  async function loginBySms(phone: string, code: string): Promise<void> {
    loading.value = true
    try {
      applyAuth(await api.loginBySms(phone, code))
      await refreshAdProgress()
    } finally {
      loading.value = false
    }
  }

  /** 微信一键登录：小程序主推方式，后端用 code 换 openid，新用户自动建号并赠送点数 */
  async function loginByWechat(code: string, nickname?: string, avatarUrl?: string): Promise<void> {
    loading.value = true
    try {
      applyAuth(await apiLoginByWechat(code, nickname, avatarUrl))
      await refreshAdProgress()
    } finally {
      loading.value = false
    }
  }

  async function register(phone: string, code: string, password?: string): Promise<void> {
    loading.value = true
    try {
      applyAuth(await api.register(phone, code, password))
      await refreshAdProgress()
    } finally {
      loading.value = false
    }
  }

  async function refreshMe(): Promise<void> {
    if (!user.value) return
    user.value = await api.fetchMe()
  }

  async function refreshAdProgress(): Promise<void> {
    if (!user.value) return
    const progress = await api.fetchAdProgress()
    adProgress.value = { watched: progress.watched, needed: progress.needed }
    user.value.credits = progress.credits
  }

  function setCredits(value: number): void {
    if (user.value) user.value.credits = value
  }

  function logout(): void {
    clearTokens()
    user.value = null
    adProgress.value = { watched: 0, needed: 10 }
  }

  return {
    user,
    loading,
    adProgress,
    isLogin,
    credits,
    nickname,
    phoneMasked,
    bootstrap,
    loginByPassword,
    loginBySms,
    loginByWechat,
    register,
    refreshMe,
    refreshAdProgress,
    setCredits,
    logout
  }
})
