package com.totop.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 掌握度色阶边界（方案 4.3.1：红 <40 / 黄 40-70 / 绿 >70）。
 *
 * 色值自 2026-11 起只有一份，在 [Tokens]；本测试同时守着「不要再长出第二套色阶」。
 *
 * 说明：函数名必须使用 ASCII 标识符（ktlint standard:function-naming），
 * 中文语义通过 @DisplayName 表达，不影响可读性。
 */
class MasteryColorTest {
    @ParameterizedTest(name = "score={0}")
    @CsvSource(
        "0.0f, RED",
        "39.9f, RED",
        "40.0f, YELLOW",
        "70.0f, YELLOW",
        "70.1f, GREEN",
        "100.0f, GREEN",
    )
    @DisplayName("色阶按阈值切换")
    fun masteryColorSwitchesByThreshold(
        score: Float,
        expected: String,
    ) {
        val actual =
            when (Tokens.masteryColor(score)) {
                Tokens.MasteryLow -> "RED"
                Tokens.MasteryMid -> "YELLOW"
                Tokens.MasteryHigh -> "GREEN"
                else -> "UNKNOWN"
            }
        assertEquals(expected, actual)
    }

    @Test
    @DisplayName("三档色值互不相同")
    fun threeLevelsAreDistinct() {
        val colors: Set<Color> =
            setOf(Tokens.MasteryLow, Tokens.MasteryMid, Tokens.MasteryHigh)
        assertEquals(3, colors.size)
    }
}
