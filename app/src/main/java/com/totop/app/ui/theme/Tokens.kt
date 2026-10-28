package com.totop.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全局设计 token（淡紫主色 + 白底）。
 *
 * 约定（沿用项目既有硬性规范）：
 * 1. **页面内禁止写死色值**，一律引用本对象或 MaterialTheme.colorScheme；
 * 2. 主色只用于关键操作与选中态，避免整屏发紫；
 * 3. 背景保持白/极浅灰白，长时间阅读不刺眼；
 * 4. 圆角与阴影取值集中在此，保证各页面观感一致。
 *
 * 配色取舍：
 * - 主色选用中饱和度的淡紫（#6D5BD0），在白底上对比度满足 WCAG AA（正文 ≥ 4.5:1）；
 * - 容器色（#EFEBFF）用于气泡与选中背景，与主色同色相、低饱和，不抢内容；
 * - 不使用渐变大面积铺底，避免低端机 GPU 掉帧，也符合项目「无动画无视差」规范。
 */
object Tokens {
    // ---------- 主色 ----------

    /** 主色：淡紫。 */
    val Primary = Color(0xFF6D5BD0)

    /** 主色深一档，用于按下态与图标。 */
    val PrimaryDark = Color(0xFF5445A6)

    /** 主色容器：气泡、选中背景、标签底。 */
    val PrimaryContainer = Color(0xFFEFEBFF)

    /** 主色容器上的文字色。 */
    val OnPrimaryContainer = Color(0xFF2B2450)

    // ---------- 背景与表面 ----------

    /** 页面背景：纯白。 */
    val Background = Color(0xFFFFFFFF)

    /**
     * 启动底色：淡紫。
     *
     * 必须与 `res/values/colors.xml` 里的 `window_background` 逐位一致。
     * 系统启动窗口（冷启动第一帧）用的是资源色，应用内启动页用的是这个常量，
     * 两者不同就会出现「先深一点、再浅一点」的三段跳，正是要修的那个观感问题。
     */
    val Splash = Color(0xFFEFEAFB)

    /** 卡片/气泡表面：白色。 */
    val Surface = Color(0xFFFFFFFF)

    /** 次要表面：浅灰白，用于分组底与输入框。 */
    val SurfaceVariant = Color(0xFFF6F5FA)

    /** 描边与分隔线。 */
    val Outline = Color(0xFFE3E1EC)

    val OutlineStrong = Color(0xFFC9C5D9)

    // ---------- 文字 ----------
    val OnSurface = Color(0xFF1C1B22)

    val OnSurfaceVariant = Color(0xFF69667A)

    // ---------- 语义色 ----------
    val Error = Color(0xFFB3261E)

    val ErrorContainer = Color(0xFFFDECEA)

    val Success = Color(0xFF2E7D5B)

    val SuccessContainer = Color(0xFFE7F5EE)

    val Warning = Color(0xFF9A6B00)

    val WarningContainer = Color(0xFFFDF3DD)

    // ---------- 掌握度三档（沿用既有语义：红<40 / 黄<=70 / 绿>70） ----------
    val MasteryLow = Color(0xFFC0392B)

    val MasteryMid = Color(0xFFC79100)

    val MasteryHigh = Color(0xFF2E7D5B)

    // ---------- 圆角 ----------
    val RadiusSmall = 8.dp

    val RadiusMedium = 12.dp

    val RadiusLarge = 16.dp

    val RadiusXLarge = 20.dp

    // ---------- 间距 ----------
    val SpaceXs = 4.dp

    val SpaceSm = 8.dp

    val SpaceMd = 12.dp

    val SpaceLg = 16.dp

    val SpaceXl = 24.dp

    /** 卡片阴影高度：低端机上避免大范围模糊。 */
    val Elevation = 1.dp

    /** 按掌握度分数取色（全应用统一入口）。 */
    fun masteryColor(score: Float): Color =
        when {
            score < 40f -> MasteryLow
            score <= 70f -> MasteryMid
            else -> MasteryHigh
        }
}
