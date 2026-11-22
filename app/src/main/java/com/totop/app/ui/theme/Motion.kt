package com.totop.app.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing

/**
 * 动效令牌：全应用唯一的时长与曲线来源。
 *
 * 为什么集中：0.9 起解除了「只做位移 + 淡入」的旧限制，动效可以按页面需要做，
 * 但**时长与缓动必须统一**——同一层级的跳转一处 260ms、另一处 400ms，
 * 用起来会觉得「这个 App 时快时慢」，却很难指出具体哪里不对。
 *
 * 取值沿用 0.5.x 起真机调过的那一组，本次只换存放位置，不改变观感。
 * 页面里新增动画时从这里取时长与 [Easing]，不要再写裸数字。
 *
 * 命名用全大写：这些是编译期常量，ktlint 与 detekt 都按常量规则要求。
 */
object Motion {
    /** 二级页进入：淡入 + 横向位移 + 轻微推近。 */
    const val ENTER_MS: Int = 260

    /** 二级页退出：比进入短，让新页面尽快成为视觉主体。 */
    const val EXIT_MS: Int = 200

    /** 底栏同级切换：只做淡入与轻微上浮。横移会把「并列」说成「层级」。 */
    const val TAB_MS: Int = 180

    /** 底部胶囊导航的滑块位移：短到不拖沓，又足以被看成「滑过去」。 */
    const val INDICATOR_MS: Int = 220

    /** 启动页淡出：与进入同量级，避免「先看首页再盖一层」的跳动。 */
    const val SPLASH_FADE_MS: Int = 260

    /**
     * 位移幅度 = 屏宽 / [SLIDE_DIVISOR]。
     *
     * 取 18 时在 360dp 窄屏上约 20dp，真机反馈「完全看不到过渡」；10 约等于 10%，
     * 方向一眼可辨，又不至于像翻书。
     */
    const val SLIDE_DIVISOR: Int = 10

    /** 上浮幅度 = 高度 / [RISE_DIVISOR]，360dp 宽屏上约 8dp。 */
    const val RISE_DIVISOR: Int = 50

    /** 进入时的起始缩放：1.5% 的轻微推近，与横移一起构成「往里走」的层次。 */
    const val ENTER_SCALE: Float = 0.985f

    /**
     * 动画时长的下限系数（相对系统「动画时长缩放」）。
     *
     * 系统设 0.5x 时 Compose 会把时长再砍一半，观感就是「没有动画」；
     * 这里反向补偿，保证等效时长不低于 0.8 倍。系统彻底关掉动画（0）时不补偿——
     * 那是用户明确的无障碍选择，不该对着干。
     */
    const val SCALE_FLOOR: Float = 0.8f

    /** 默认缓动：进入、退出、切换、滑块统一用它。 */
    val Easing: Easing = FastOutSlowInEasing
}
