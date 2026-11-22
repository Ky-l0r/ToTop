package com.totop.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 排版方案。
 *
 * 性能约束（方案 7.1）：一律使用系统字体，不打包字体文件，避免增加包体与首屏解析开销；
 * 字号整体偏大，适配中学生长时间阅读题目与步骤。
 */
private val Default = FontFamily.SansSerif

val TotopTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                lineHeight = 34.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 30.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                lineHeight = 24.sp,
            ),
        /**
         * 卡片标题、列表主标题。
         *
         * 这一级在 2026-11 之前没有定义，页面里用的是 Material3 的默认值（14sp / 20sp / Medium）。
         * 显式写出来是为了让「只能从 Typography 取字号」这条规则真正成立——
         * 否则改字号要翻 19 个页面。取值与默认值一致，本版不制造视觉突变。
         */
        titleSmall =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 26.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        /**
         * 卡片副标题、元信息（时间、次数、科目）。
         *
         * 全仓用得最多的一级（一百余处），此前同样走的 Material3 默认值（11sp / 16sp / Medium）。
         * 取值保持不变，理由同 [titleSmall]。
         */
        labelSmall =
            TextStyle(
                fontFamily = Default,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
    )
