package com.totop.app.ui.theme

import androidx.compose.ui.graphics.Color

// 主色：低饱和青绿，长时间阅读不刺眼，且与纯白卡片对比度满足 WCAG AA
val TotopGreen = Color(0xFF1F6F5C)
val TotopGreenDark = Color(0xFF0E4A3C)
val TotopGreenLight = Color(0xFFB8E0D2)

// 强调色：暖琥珀，仅用于关键操作与提示
val TotopAmber = Color(0xFFB4690E)
val TotopAmberLight = Color(0xFFF5D9B0)

// 中性色
val TotopSurface = Color(0xFFFBFBF8)
val TotopSurfaceDark = Color(0xFF121614)
val TotopOnSurface = Color(0xFF1B1C1A)
val TotopOnSurfaceDark = Color(0xFFE3E3DF)
val TotopOutline = Color(0xFF757873)
val TotopOutlineDark = Color(0xFF8E918C)

// 错误色
val TotopError = Color(0xFF9B2C2C)
val TotopErrorLight = Color(0xFFF3C6C6)

/**
 * 掌握度色阶（方案 FR-3 / 4.3.1）：
 * 红 <40 / 黄 40-70 / 绿 >70。
 * 全应用统一使用该函数取色，禁止在页面内私自定义色值。
 */
val MasteryRed = Color(0xFFC0392B)
val MasteryYellow = Color(0xFFC79100)
val MasteryGreen = Color(0xFF1F6F5C)

fun masteryColor(score: Float): Color =
    when {
        score < 40f -> MasteryRed
        score <= 70f -> MasteryYellow
        else -> MasteryGreen
    }
