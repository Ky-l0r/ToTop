<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()

onLaunch(() => {
  // 冷启动时静默恢复登录态
  userStore.bootstrap()

  // 微信小程序：静默检查新版本，下次启动时用新版（避免用户长期停留在旧版）
  // #ifdef MP-WEIXIN
  const updateManager = uni.getUpdateManager && uni.getUpdateManager()
  if (updateManager) {
    updateManager.onUpdateReady(() => {
      uni.showModal({
        title: '更新提示',
        content: '新版本已准备好，是否重启应用？',
        success: (res) => {
          if (res.confirm) updateManager.applyUpdate()
        }
      })
    })
  }
  // #endif
})

onShow(() => {
  // 回到前台时刷新点数（可能在别处看过广告或完成支付）
  if (userStore.isLogin) {
    userStore.refreshMe().catch(() => {})
  }
})
</script>

<style lang="scss">
/* ==========================================================================
   全局样式 · 紫色主题
   注意：微信小程序中 view 不支持 :active 伪类，点击反馈统一用 hover-class="tt-hover"
   ========================================================================== */

page {
  background-color: $tt-bg;
  color: $tt-text;
  font-size: 28rpx;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif;
  line-height: 1.5;
}

/* 点击反馈（配合 hover-class 使用） */
.tt-hover {
  transform: scale(0.97);
  opacity: 0.88;
}

.tt-hover-light {
  opacity: 0.7;
}

/* ---------------------------------------------------------------- 布局 */

.tt-page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.tt-card {
  background-color: $tt-card;
  border-radius: $tt-radius;
  padding: 28rpx;
  margin-bottom: 24rpx;
  box-shadow: $tt-shadow;
}

.tt-card--flush {
  padding: 0;
  overflow: hidden;
}

.tt-card__title {
  font-size: 30rpx;
  font-weight: 600;
  color: $tt-text;
  margin-bottom: 20rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tt-card__subtitle {
  font-size: 24rpx;
  color: $tt-text-weak;
  font-weight: 400;
}

.tt-row {
  display: flex;
  align-items: center;
}

.tt-row--between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tt-flex1 {
  flex: 1;
  min-width: 0;
}

.tt-muted {
  color: $tt-text-sub;
  font-size: 24rpx;
}

.tt-weak {
  color: $tt-text-weak;
  font-size: 22rpx;
}

.tt-divider {
  height: 1rpx;
  background-color: $tt-border;
  margin: 20rpx 0;
}

.tt-empty {
  padding: 80rpx 0;
  text-align: center;
  color: $tt-text-weak;
  font-size: 26rpx;
}

/* ---------------------------------------------------------------- 按钮 */

.tt-btn {
  height: 92rpx;
  line-height: 92rpx;
  text-align: center;
  border-radius: 46rpx;
  font-size: 30rpx;
  font-weight: 600;
  background: $tt-gradient;
  color: $tt-text-inverse;
  box-shadow: 0 10rpx 24rpx rgba(124, 92, 255, 0.24);
  transition: transform 0.16s ease, opacity 0.16s ease;
}

.tt-btn--ghost {
  background: $tt-primary-light;
  color: $tt-primary;
  box-shadow: none;
}

.tt-btn--plain {
  background: transparent;
  color: $tt-text-sub;
  border: 1rpx solid $tt-border;
  box-shadow: none;
}

.tt-btn--ad {
  background: $tt-gradient-accent;
  box-shadow: 0 10rpx 24rpx rgba(255, 143, 61, 0.26);
}

.tt-btn--sm {
  height: 68rpx;
  line-height: 68rpx;
  font-size: 26rpx;
  border-radius: 34rpx;
  padding: 0 32rpx;
}

.tt-btn--disabled {
  background: #d9d4ea !important;
  color: #ffffff !important;
  box-shadow: none !important;
}

/* ---------------------------------------------------------------- 标签 */

.tt-tag {
  display: inline-flex;
  align-items: center;
  height: 40rpx;
  padding: 0 16rpx;
  border-radius: 20rpx;
  font-size: 22rpx;
  background-color: $tt-primary-light;
  color: $tt-primary;
}

.tt-tag--warn {
  background-color: rgba(245, 158, 11, 0.12);
  color: $tt-warning;
}

.tt-tag--danger {
  background-color: rgba(245, 84, 79, 0.12);
  color: $tt-danger;
}

.tt-tag--success {
  background-color: rgba(34, 197, 94, 0.12);
  color: $tt-success;
}

.tt-tag--ad {
  background-color: $tt-accent-soft;
  color: $tt-accent;
}

.tt-demo-badge {
  display: inline-flex;
  align-items: center;
  height: 36rpx;
  padding: 0 14rpx;
  border-radius: 18rpx;
  font-size: 20rpx;
  background-color: $tt-accent-soft;
  color: $tt-accent;
}

/* ---------------------------------------------------------------- 输入 */

.tt-input {
  height: 92rpx;
  background-color: #f5f3fb;
  border-radius: $tt-radius-sm;
  padding: 0 24rpx;
  font-size: 30rpx;
  color: $tt-text;
}

/* ==========================================================================
   动画
   全部只使用 transform / opacity，走 GPU 合成层，避免小程序重排
   ========================================================================== */

@keyframes tt-fade-up {
  from {
    opacity: 0;
    transform: translate3d(0, 24rpx, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@keyframes tt-fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes tt-pop {
  0% {
    opacity: 0;
    transform: scale3d(0.86, 0.86, 1);
  }
  60% {
    opacity: 1;
    transform: scale3d(1.03, 1.03, 1);
  }
  100% {
    opacity: 1;
    transform: scale3d(1, 1, 1);
  }
}

@keyframes tt-slide-right {
  from {
    opacity: 0;
    transform: translate3d(-20rpx, 0, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

/* 解锁时的锁体抖动 → 弹开 */
@keyframes tt-lock-shake {
  0%,
  100% {
    transform: rotate(0deg);
  }
  20% {
    transform: rotate(-12deg);
  }
  40% {
    transform: rotate(10deg);
  }
  60% {
    transform: rotate(-6deg);
  }
  80% {
    transform: rotate(4deg);
  }
}

@keyframes tt-pulse {
  0% {
    transform: scale3d(1, 1, 1);
    opacity: 1;
  }
  50% {
    transform: scale3d(1.08, 1.08, 1);
    opacity: 0.85;
  }
  100% {
    transform: scale3d(1, 1, 1);
    opacity: 1;
  }
}

/* 骨架屏扫光 */
@keyframes tt-shimmer {
  0% {
    background-position: -200% 0;
  }
  100% {
    background-position: 200% 0;
  }
}

/* 进度条内部的光泽流动 */
@keyframes tt-glow {
  0% {
    transform: translate3d(-100%, 0, 0);
  }
  100% {
    transform: translate3d(100%, 0, 0);
  }
}

.tt-anim-fade-up {
  animation: tt-fade-up 0.42s cubic-bezier(0.22, 0.68, 0, 1) both;
}

.tt-anim-fade-in {
  animation: tt-fade-in 0.36s ease both;
}

.tt-anim-pop {
  animation: tt-pop 0.44s cubic-bezier(0.34, 1.4, 0.64, 1) both;
}

.tt-anim-slide-right {
  animation: tt-slide-right 0.4s cubic-bezier(0.22, 0.68, 0, 1) both;
}

.tt-anim-lock-shake {
  animation: tt-lock-shake 0.6s ease both;
}

.tt-anim-pulse {
  animation: tt-pulse 2s ease-in-out infinite;
}

/* 阶梯延迟：列表逐项入场，避免一次性触发过多动画 */
.tt-delay-1 {
  animation-delay: 0.05s;
}
.tt-delay-2 {
  animation-delay: 0.1s;
}
.tt-delay-3 {
  animation-delay: 0.16s;
}
.tt-delay-4 {
  animation-delay: 0.22s;
}
.tt-delay-5 {
  animation-delay: 0.28s;
}

/* 骨架屏块 */
.tt-skeleton {
  background: linear-gradient(90deg, #f0eef8 25%, #f7f5fd 37%, #f0eef8 63%);
  background-size: 400% 100%;
  animation: tt-shimmer 1.4s ease infinite;
  border-radius: $tt-radius-sm;
}

/* 尊重「减少动态效果」的系统设置 */
@media (prefers-reduced-motion: reduce) {
  .tt-anim-fade-up,
  .tt-anim-fade-in,
  .tt-anim-pop,
  .tt-anim-slide-right,
  .tt-anim-lock-shake,
  .tt-anim-pulse,
  .tt-skeleton {
    animation: none !important;
  }
}
</style>
