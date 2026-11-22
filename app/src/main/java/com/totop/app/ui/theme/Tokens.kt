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

    // ---------- 讲解内容的两种语义色 ----------

    /**
     * 「解题思路」色：偏紫的深灰蓝。
     *
     * 讲解里有两类内容必须一眼分得开：**为什么这样想**（思路、依据、判断）与
     * **具体怎么算**（代入、变形、得数）。全部用同一个墨色时，一屏十几行公式
     * 和说明混在一起，学生读三行就不知道自己读到哪了。
     * 思路用与主色同色系的紫，计算用青色，两者在白底上对比度都在 6:1 以上（WCAG AA）。
     */
    val Reasoning = Color(0xFF5B4FA8)

    /** 思路块的浅底。 */
    val ReasoningContainer = Color(0xFFF3F0FF)

    /** 「计算过程」色：偏冷的青蓝，与思路色色相拉开。 */
    val Calculation = Color(0xFF1B6B7A)

    /** 计算块的浅底：把连续几行算式聚成一块，避免公式堆叠成一片。 */
    val CalculationContainer = Color(0xFFE9F5F7)

    // ---------- 掌握度四档（红<40 / 黄<=70 / 绿>70 / 未练习灰） ----------
    //
    // 取值口径：采用知识点页一直在用的那一套（低饱和、同明度），
    // 而不是早先 `Color.kt` 里那套饱和度更高的 #C0392B / #C79100。
    // 2026-11 收敛前，同一个「红」在首页与知识点页是两个值，截图对不上。
    val MasteryLow = Color(0xFF9B2C2C)

    val MasteryMid = Color(0xFFB4690E)

    val MasteryHigh = Color(0xFF1F6F5C)

    /** 未练习：中性灰，不参与红黄绿的语义。 */
    val MasteryNone = Color(0xFF9AA0A6)

    /**
     * 错因饼图的调色板（七类固定顺序）。
     *
     * 旧版是七个饱和度拉满的深色（墨绿、赭石、藏蓝、紫、砖红、青、灰），
     * 色相之间没有关系，同一块饼里出现三四块就非常刺眼。现在改为一组
     * **同明度、同饱和度**的柔和色，冷暖交替排列：
     * 1. 相邻扇区永远一暖一冷，不会糊成一片；
     * 2. 明度交替（深-中-深-中…），黑白打印也能靠深浅分出边界；
     * 3. 与主题的紫、语义的成功绿/警示橙同族，不破坏整页观感。
     */
    val MasteryPiePalette: List<Color> =
        listOf(
            Color(0xFFC2703A),
            Color(0xFF7B5AA6),
            Color(0xFF3F7FA6),
            Color(0xFFC9A227),
            Color(0xFF4E8C6A),
            Color(0xFF8C6A4F),
            Color(0xFF8A8794),
        )

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

    /** 卡片阴影高度：统一 1dp，够分层又不堆模糊。 */
    val Elevation = 1.dp

    /** 按掌握度分数取色（全应用统一入口）。 */
    fun masteryColor(score: Float): Color =
        when {
            score < 40f -> MasteryLow
            score <= 70f -> MasteryMid
            else -> MasteryHigh
        }
}
