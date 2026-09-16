/** 微信小程序登录。 */
import { request } from './request'
import type { AuthResult } from '@/types'

/**
 * 用 wx.login 拿到的 code 换取登录态。
 * 后端负责调 code2Session 换 openid：老用户直接登录，新用户自动建号并赠送点数。
 */
export function loginByWechat(
  code: string,
  nickname?: string,
  avatarUrl?: string
): Promise<AuthResult> {
  return request<AuthResult>({
    url: '/auth/wechat/login',
    method: 'POST',
    data: { code, nickname, avatar_url: avatarUrl }
  })
}
