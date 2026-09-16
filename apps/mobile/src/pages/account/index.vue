<template>
  <view class="tt-page">
    <!-- ======================= 未登录 ======================= -->
    <template v-if="!userStore.isLogin">
      <view class="brand">
        <view class="brand__logo">ToTop</view>
        <text class="brand__slogan">拍照导试卷 · AI 找准每一个失分点</text>
        <view class="brand__features">
          <text class="brand__feature">逐题归因</text>
          <text class="brand__dot">·</text>
          <text class="brand__feature">定位知识模块</text>
          <text class="brand__dot">·</text>
          <text class="brand__feature">生成提升计划</text>
        </view>
      </view>

      <view class="tt-card">
        <view class="tabs">
          <view class="tabs__item" :class="{ 'tabs__item--on': mode === 'sms' }" @tap="mode = 'sms'">
            验证码登录
          </view>
          <view class="tabs__item" :class="{ 'tabs__item--on': mode === 'pwd' }" @tap="mode = 'pwd'">
            密码登录
          </view>
        </view>

        <input
          v-model="phone"
          class="tt-input field"
          type="number"
          maxlength="11"
          placeholder="请输入手机号"
          placeholder-class="ph"
        />

        <view v-if="mode === 'sms'" class="code-row">
          <input
            v-model="code"
            class="tt-input tt-flex1"
            type="number"
            maxlength="6"
            placeholder="6 位验证码"
            placeholder-class="ph"
          />
          <view class="code-btn" :class="{ 'code-btn--disabled': counting }" @tap="getCode">
            {{ counting ? `${countdown}s 后重发` : '获取验证码' }}
          </view>
        </view>

        <input
          v-else
          v-model="password"
          class="tt-input field"
          password
          maxlength="20"
          placeholder="请输入密码（6-20 位）"
          placeholder-class="ph"
        />

        <view class="tt-btn submit-btn" :class="{ 'tt-btn--disabled': !canSubmit }" hover-class="tt-hover" @tap="submit">
          {{ mode === 'sms' ? '登录 / 注册' : '登录' }}
        </view>

        <!-- #ifdef MP-WEIXIN -->
        <view class="divider-with-text"><text>或</text></view>
        <view class="tt-btn wechat-btn" hover-class="tt-hover" @tap="handleWechatLogin">微信一键登录</view>
        <!-- #endif -->

        <view class="login-tip">新账号自动赠送 1 点数，可用于跳过 1 段广告直接查看分析</view>

        <view class="agree" hover-class="tt-hover-light" @tap="agreed = !agreed">
          <view class="agree__box" :class="{ 'agree__box--on': agreed }">
            <text v-if="agreed">✓</text>
          </view>
          <text class="agree__text">我已阅读并同意《用户协议》与《隐私政策》</text>
        </view>
      </view>

      <view v-if="isDemo" class="demo-tip">演示模式：验证码固定为 123456，任意手机号均可登录</view>
    </template>

    <!-- ======================= 已登录 ======================= -->
    <template v-else>
      <!-- 用户信息 -->
      <view class="profile">
        <view class="profile__avatar">
          <text>{{ avatarText }}</text>
        </view>
        <view class="tt-flex1">
          <text class="profile__name">{{ userStore.nickname }}</text>
          <text class="profile__phone">{{ userStore.phoneMasked }}</text>
        </view>
        <view v-if="isDemo" class="tt-demo-badge">演示数据</view>
      </view>

      <!-- 点数与广告 -->
      <view class="tt-card">
        <view class="credits">
          <view>
            <text class="tt-muted">点数余额</text>
            <text class="credits__value">{{ userStore.credits }}</text>
          </view>
          <view class="tt-tag">1 点数 = 1 次 AI 分析</view>
        </view>

        <view class="tt-divider" />

        <view class="tt-row--between">
          <text class="section-title">看视频得点数</text>
          <text class="tt-weak">
            {{ remaining === 0 ? '再观看 1 次即可获得 1 点数' : `再观看 ${remaining} 次即可获得 1 点数` }}
          </text>
        </view>

        <view class="ad-progress">
          <view class="ad-progress__bar" :style="{ width: adPercent + '%' }" />
        </view>
        <view class="ad-progress__label">
          <text>{{ adProgress.watched }} / {{ adProgress.needed }} 次</text>
          <text class="tt-weak">每 {{ adProgress.needed }} 次广告 = 1 点数</text>
        </view>

        <view class="tt-btn tt-btn--ghost ad-btn" hover-class="tt-hover" @tap="watchAd">观看激励视频</view>
      </view>

      <!-- 充值：微信小程序 iOS 端禁止虚拟支付，因此 iOS 用户只能看视频获取点数 -->
      <view class="tt-card">
        <view class="tt-card__title">
          <text>充值点数</text>
          <text class="tt-card__subtitle">{{ isIOS ? 'iOS 端暂不支持' : '微信支付' }}</text>
        </view>

        <view v-if="isIOS" class="ios-tip">
          受微信小程序平台规则限制，iOS 端不能购买点数。你可以观看激励视频免费获取点数，或在安卓设备上充值后跨设备使用同一账号。
        </view>

        <template v-else>
          <view class="plans">
            <view
              v-for="plan in visiblePlans"
              :key="plan.id"
              class="plan"
              :class="{ 'plan--on': selectedPlanId === plan.id }"
              hover-class="tt-hover"
              @tap="selectedPlanId = plan.id"
            >
              <text class="plan__credits">{{ plan.credits }} 点</text>
              <text class="plan__price">{{ yuan(plan.price_cents) }}</text>
              <text v-if="plan.recommended" class="plan__badge">超值</text>
            </view>
          </view>

          <view class="tt-btn buy-btn" hover-class="tt-hover" @tap="buy">立即充值</view>
          <view class="hint">
            通过微信支付结算，单价 ¥1.00 / 点，支付成功后点数即时到账。点数在解锁分析阶段时可直接跳过广告。
          </view>
        </template>
      </view>

      <!-- 点数明细 -->
      <view class="tt-card">
        <view class="tt-card__title">
          <text>点数明细</text>
          <text class="tt-card__subtitle">最近 {{ ledger.length }} 条</text>
        </view>
        <view v-if="!ledger.length" class="tt-empty">暂无记录</view>
        <view v-for="item in ledger" :key="item.id" class="ledger">
          <view class="tt-flex1">
            <text class="ledger__remark">{{ item.remark || reasonText(item.reason) }}</text>
            <text class="tt-weak">{{ dateTimeShort(item.created_at) }}</text>
          </view>
          <view class="ledger__right">
            <text :class="['ledger__delta', item.delta > 0 ? 'is-up' : 'is-down']">
              {{ item.delta > 0 ? `+${item.delta}` : item.delta }}
            </text>
            <text class="tt-weak">余 {{ item.balance_after }}</text>
          </view>
        </view>
      </view>

      <!-- 设置 -->
      <view class="tt-card">
        <view class="menu" hover-class="tt-hover-light" @tap="changePassword">
          <text class="menu__label">绑定 / 修改密码</text>
          <text class="menu__arrow">›</text>
        </view>
        <view class="menu" hover-class="tt-hover-light" @tap="showAgreement">
          <text class="menu__label">用户协议与隐私政策</text>
          <text class="menu__arrow">›</text>
        </view>
        <view class="menu" hover-class="tt-hover-light" @tap="showAbout">
          <text class="menu__label">关于 ToTop</text>
          <text class="menu__arrow">v0.1.0</text>
        </view>
        <view class="menu menu--danger" hover-class="tt-hover-light" @tap="logout">
          <text class="menu__label">退出登录</text>
          <text class="menu__arrow">›</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import * as api from '@/api'
import { ADS_PER_CREDIT, USE_MOCK } from '@/api/config'
import { ApiError } from '@/api/request'
import { useUserStore } from '@/store/user'
import { dateTimeShort, yuan } from '@/utils/format'
import { initRewardedAd, playRewardedAd } from '@/utils/ad'
import type { AdProgress, CreditLedgerItem, Plan } from '@/types'

const userStore = useUserStore()
const isDemo = USE_MOCK

const mode = ref<'sms' | 'pwd'>('sms')
const phone = ref('')
const code = ref('')
const password = ref('')
const agreed = ref(false)
const counting = ref(false)
const countdown = ref(60)

const plans = ref<Plan[]>([])
const selectedPlanId = ref('')
const ledger = ref<CreditLedgerItem[]>([])
const adProgress = ref<AdProgress>({ watched: 0, needed: ADS_PER_CREDIT, per_reward: 1, credits: 0 })

const isIOS = computed(() => {
  try {
    return String(uni.getSystemInfoSync().platform || '').toLowerCase() === 'ios'
  } catch (e) {
    return false
  }
})

const avatarText = computed(() => {
  const name = userStore.nickname
  return name ? name.slice(0, 1) : 'T'
})

const phoneValid = computed(() => /^1[3-9]\d{9}$/.test(phone.value))
const canSubmit = computed(() => {
  if (!phoneValid.value || !agreed.value) return false
  return mode.value === 'sms' ? /^\d{6}$/.test(code.value) : password.value.length >= 6
})

const remaining = computed(() => Math.max(0, adProgress.value.needed - adProgress.value.watched))
const adPercent = computed(() => {
  if (!adProgress.value.needed) return 0
  return Math.min(100, Math.round((adProgress.value.watched / adProgress.value.needed) * 100))
})

/** iOS 小程序不能售卖虚拟商品，直接不展示任何充值套餐 */
const visiblePlans = computed(() => {
  if (isIOS.value) return [] as Plan[]
  const list = plans.value.filter((p) => p.channel !== 'apple_iap')
  if (list.length && !list.some((p) => p.id === selectedPlanId.value)) {
    selectedPlanId.value = (list.find((p) => p.recommended) || list[0]).id
  }
  return list
})

/* ------------------------------------------------------------- 登录注册 */

function getCode(): void {
  if (counting.value) return
  if (!phoneValid.value) {
    uni.showToast({ title: '请输入正确的手机号', icon: 'none' })
    return
  }
  api
    .sendSmsCode(phone.value, 'login')
    .then((res) => {
      counting.value = true
      countdown.value = 60
      const timer = setInterval(() => {
        countdown.value -= 1
        if (countdown.value <= 0) {
          clearInterval(timer)
          counting.value = false
        }
      }, 1000)
      if (res.dev_code) {
        code.value = res.dev_code
        uni.showToast({ title: `验证码 ${res.dev_code}（演示）`, icon: 'none' })
      } else {
        uni.showToast({ title: '验证码已发送', icon: 'none' })
      }
    })
    .catch((e) => uni.showToast({ title: e?.message || '发送失败', icon: 'none' }))
}

async function submit(): Promise<void> {
  if (!agreed.value) {
    uni.showToast({ title: '请先同意用户协议', icon: 'none' })
    return
  }
  if (!canSubmit.value) {
    uni.showToast({ title: mode.value === 'sms' ? '请填写手机号与 6 位验证码' : '请输入手机号与 6 位以上密码', icon: 'none' })
    return
  }

  uni.showLoading({ title: '处理中', mask: true })
  try {
    if (mode.value === 'pwd') {
      await userStore.loginByPassword(phone.value, password.value)
    } else {
      try {
        await userStore.loginBySms(phone.value, code.value)
      } catch (e) {
        // 手机号未注册时自动注册，注册成功即赠 1 点数
        if (e instanceof ApiError && (e.code === 40401 || e.code === 40004)) {
          await userStore.register(phone.value, code.value, undefined)
        } else {
          throw e
        }
      }
    }
    uni.hideLoading()
    uni.showToast({ title: '登录成功', icon: 'none' })
    await loadAccountData()
  } catch (e: any) {
    uni.hideLoading()
    uni.showToast({ title: e?.message || '登录失败', icon: 'none' })
  }
}

/* -------------------------------------------------------- 微信一键登录 */

async function handleWechatLogin(): Promise<void> {
  if (!agreed.value) {
    uni.showToast({ title: '请先阅读并同意用户协议', icon: 'none' })
    return
  }
  // #ifdef MP-WEIXIN
  uni.login({
    provider: 'weixin',
    success: async (res) => {
      if (!res.code) {
        uni.showToast({ title: '微信授权失败，请重试', icon: 'none' })
        return
      }
      try {
        await userStore.loginByWechat(res.code)
        await loadAccountData()
        uni.showToast({ title: '登录成功', icon: 'none' })
      } catch (e: any) {
        uni.showToast({ title: e?.message || '登录失败', icon: 'none' })
      }
    },
    fail: () => uni.showToast({ title: '微信授权失败，请重试', icon: 'none' })
  })
  // #endif
  // #ifndef MP-WEIXIN
  uni.showToast({ title: '请在小程序内使用微信一键登录', icon: 'none' })
  // #endif
}

/* ---------------------------------------------------------------- 广告 */

async function watchAd(): Promise<void> {
  const played = await playRewardedAd()
  if (!played.ok) {
    if (played.reason) uni.showToast({ title: played.reason, icon: 'none' })
    return
  }
  try {
    const result = await api.reportAdReward(
      'wechat',
      `ad_${Date.now()}_${Math.floor(Math.random() * 1e6)}`,
      30
    )
    adProgress.value = {
      ...adProgress.value,
      watched: result.progress.watched,
      needed: result.progress.needed,
      credits: result.credits
    }
    userStore.setCredits(result.credits)
    if (result.credited) {
      uni.showModal({
        title: '恭喜获得 1 点数',
        content: `已累计观看 ${adProgress.value.needed} 次广告，获得 1 点数，当前余额 ${result.credits} 点。`,
        showCancel: false
      })
    } else {
      uni.showToast({ title: `已记录，还差 ${adProgress.value.needed - result.progress.watched} 次`, icon: 'none' })
    }
    await loadLedger()
  } catch (e: any) {
    uni.showToast({ title: e?.message || '记录失败', icon: 'none' })
  }
}

/* ---------------------------------------------------------------- 充值 */

async function buy(): Promise<void> {
  const plan = plans.value.find((p) => p.id === selectedPlanId.value)
  if (!plan) return
  uni.showLoading({ title: '创建订单', mask: true })
  try {
    const order = await api.createOrder(plan.id, plan.channel)
    uni.hideLoading()
    uni.showModal({
      title: '订单已创建',
      content: isDemo
        ? `演示模式：${plan.credits} 点数，应付 ${yuan(order.amount_cents)}。真实环境会在此调起微信支付。`
        : `应付 ${yuan(order.amount_cents)}，即将调起支付。`,
      showCancel: false
    })
    await loadAccountData()
  } catch (e: any) {
    uni.hideLoading()
    uni.showToast({ title: e?.message || '下单失败', icon: 'none' })
  }
}

/* ---------------------------------------------------------------- 其他 */

function reasonText(reason: CreditLedgerItem['reason']): string {
  const map: Record<string, string> = {
    register_bonus: '注册赠送',
    analysis: 'AI 分析消耗',
    ad_reward: '观看广告奖励',
    purchase: '充值购买',
    admin_adjust: '系统调整',
    refund: '退回'
  }
  return map[reason] || '点数变动'
}

function changePassword(): void {
  uni.showModal({
    title: '设置密码',
    editable: true,
    placeholderText: '请输入新密码（6-20 位）',
    success: async (res) => {
      if (!res.confirm) return
      const newPassword = (res.content || '').trim()
      if (newPassword.length < 6) {
        uni.showToast({ title: '密码至少 6 位', icon: 'none' })
        return
      }
      try {
        await api.setPassword(undefined, newPassword)
        uni.showToast({ title: '密码已设置', icon: 'none' })
      } catch (e: any) {
        uni.showToast({ title: e?.message || '设置失败', icon: 'none' })
      }
    }
  })
}

function showAgreement(): void {
  uni.showModal({
    title: '用户协议与隐私政策',
    content:
      '我们仅收集手机号用于账号登录，收集你主动上传的试卷与答题卡图片用于 AI 分析。图片会加密存储在你的账号下，不会用于模型训练，也不会向第三方出售。你可以随时在「我的」中删除考试记录以清除对应数据。',
    showCancel: false,
    confirmText: '我知道了'
  })
}

function showAbout(): void {
  uni.showModal({
    title: '关于 ToTop',
    content: 'ToTop v0.1.0\n拍照导入试卷与答题卡，由多模态 AI 分析失分知识点、定位知识模块并生成提升指南。',
    showCancel: false
  })
}

function logout(): void {
  uni.showModal({
    title: '退出登录',
    content: '退出后需要重新登录才能查看分析结果，确定退出吗？',
    success: (res) => {
      if (res.confirm) {
        userStore.logout()
        uni.showToast({ title: '已退出', icon: 'none' })
      }
    }
  })
}

/* ------------------------------------------------------------ 数据加载 */

async function loadLedger(): Promise<void> {
  if (!userStore.isLogin) return
  const result = await api.fetchLedger(1, 20)
  ledger.value = result.items
}

async function loadAccountData(): Promise<void> {
  if (!userStore.isLogin) return
  try {
    const [planList, adInfo] = await Promise.all([api.fetchPlans(), api.fetchAdProgress()])
    plans.value = planList
    adProgress.value = adInfo
    userStore.setCredits(adInfo.credits)
    await loadLedger()
  } catch (e) {
    console.warn('[account] 加载账户数据失败', e)
  }
}

onShow(() => {
  // 提前拉取激励视频，用户点击「观看」时能更快出片
  initRewardedAd()
  if (userStore.isLogin) {
    userStore.refreshMe().catch(() => {})
    loadAccountData()
  }
})
</script>

<style lang="scss" scoped>

/* ------------------------------------------------------------ 登录方式 */

.divider-with-text {
  display: flex;
  align-items: center;
  margin: 28rpx 0 20rpx;
  color: $tt-text-weak;
  font-size: 22rpx;
}

.divider-with-text::before,
.divider-with-text::after {
  content: '';
  flex: 1;
  height: 1rpx;
  background-color: $tt-border;
}

.wechat-btn {
  background: #ffffff;
  color: #07c160;
  border: 2rpx solid rgba(7, 193, 96, 0.3);
  box-shadow: none;
}

/* iOS 端小程序不允许虚拟支付时的说明块 */
.ios-tip {
  font-size: 24rpx;
  line-height: 1.7;
  color: $tt-text-sub;
  background-color: $tt-primary-light;
  border-radius: $tt-radius-sm;
  padding: 22rpx;
}
/* ---------------------------------------------------------- 未登录 */

.brand {
  padding: 60rpx 0 50rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.brand__logo {
  font-size: 64rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  color: $tt-primary;
}

.brand__slogan {
  margin-top: 18rpx;
  font-size: 26rpx;
  color: $tt-text-sub;
}

.brand__features {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
}

.brand__feature {
  font-size: 22rpx;
  color: $tt-text-weak;
}

.brand__dot {
  margin: 0 12rpx;
  color: #d3d9e6;
}

.tabs {
  display: flex;
  margin-bottom: 30rpx;
}

.tabs__item {
  flex: 1;
  text-align: center;
  font-size: 28rpx;
  color: $tt-text-weak;
  padding-bottom: 16rpx;
  border-bottom: 4rpx solid transparent;
}

.tabs__item--on {
  color: $tt-primary;
  font-weight: 600;
  border-bottom-color: $tt-primary;
}

.field {
  margin-bottom: 20rpx;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 20rpx;
}

.code-btn {
  flex-shrink: 0;
  height: 92rpx;
  line-height: 92rpx;
  padding: 0 28rpx;
  border-radius: 16rpx;
  font-size: 26rpx;
  color: $tt-primary;
  background-color: $tt-primary-light;
}

.code-btn--disabled {
  color: $tt-text-weak;
  background-color: #f1f4fa;
}

.submit-btn {
  margin-top: 12rpx;
}

.login-tip {
  margin-top: 20rpx;
  font-size: 22rpx;
  color: $tt-text-weak;
  line-height: 1.6;
}

.agree {
  display: flex;
  align-items: center;
  margin-top: 26rpx;
}

.agree__box {
  width: 32rpx;
  height: 32rpx;
  border-radius: 8rpx;
  border: 2rpx solid #ccd6ea;
  margin-right: 12rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  color: #ffffff;
}

.agree__box--on {
  background-color: $tt-primary;
  border-color: $tt-primary;
}

.agree__text {
  font-size: 22rpx;
  color: $tt-text-sub;
}

.demo-tip {
  text-align: center;
  font-size: 22rpx;
  color: $tt-accent;
}

/* ---------------------------------------------------------- 已登录 */

.profile {
  display: flex;
  align-items: center;
  padding: 30rpx 32rpx;
  background: linear-gradient(135deg, #8b6cff 0%, #6344e8 100%);
  border-radius: $tt-radius-lg;
  margin-bottom: 24rpx;
  box-shadow: 0 10rpx 30rpx rgba(76, 111, 255, 0.22);
}

.profile__avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 48rpx;
  background-color: rgba(255, 255, 255, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 24rpx;
  font-size: 40rpx;
  font-weight: 700;
  color: #ffffff;
}

.profile__name {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
}

.profile__phone {
  display: block;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.75);
  margin-top: 8rpx;
}

.credits {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.credits__value {
  display: block;
  font-size: 64rpx;
  font-weight: 700;
  color: $tt-primary;
  line-height: 1.2;
}

.section-title {
  font-size: 28rpx;
  font-weight: 600;
  color: $tt-text;
}

.ad-progress {
  height: 16rpx;
  background-color: #eef1f8;
  border-radius: 8rpx;
  overflow: hidden;
  margin: 20rpx 0 12rpx;
}

.ad-progress__bar {
  height: 100%;
  background: linear-gradient(90deg, #ff9f45, #ff7a45);
  border-radius: 8rpx;
  transition: width 0.4s ease;
}

.ad-progress__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 24rpx;
  color: $tt-text-sub;
}

.ad-btn {
  margin-top: 24rpx;
}

.plans {
  display: flex;
  gap: 16rpx;
}

.plan {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 26rpx 0;
  border-radius: 16rpx;
  background-color: #f6f8fc;
  border: 2rpx solid transparent;
}

.plan--on {
  background-color: $tt-primary-light;
  border-color: $tt-primary;
}

.plan__credits {
  font-size: 30rpx;
  font-weight: 700;
  color: $tt-text;
}

.plan__price {
  font-size: 24rpx;
  color: $tt-text-sub;
  margin-top: 8rpx;
}

.plan__badge {
  position: absolute;
  top: -12rpx;
  right: -6rpx;
  font-size: 20rpx;
  color: #ffffff;
  background-color: $tt-accent;
  border-radius: 16rpx;
  padding: 2rpx 12rpx;
}

.buy-btn {
  margin-top: 28rpx;
}

.hint {
  margin-top: 18rpx;
  font-size: 22rpx;
  color: $tt-text-weak;
  line-height: 1.6;
}

.ledger {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid $tt-border;

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }
}

.ledger__remark {
  display: block;
  font-size: 26rpx;
  color: $tt-text;
  margin-bottom: 6rpx;
}

.ledger__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.ledger__delta {
  font-size: 30rpx;
  font-weight: 700;
}

.ledger__delta.is-up {
  color: $tt-success;
}

.ledger__delta.is-down {
  color: $tt-danger;
}

.menu {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 96rpx;
  border-bottom: 1rpx solid $tt-border;

  &:last-child {
    border-bottom: none;
  }
}

.menu__label {
  font-size: 28rpx;
  color: $tt-text;
}

.menu__arrow {
  font-size: 26rpx;
  color: $tt-text-weak;
}

.menu--danger .menu__label {
  color: $tt-danger;
}
</style>
