package com.totop.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * 掌握度色阶边界（方案 4.3.1：红 <40 / 黄 40-70 / 绿 >70）。
 * 该测试同时用于验证 JUnit5 测试链路可用。
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
            when (masteryColor(score)) {
                MasteryRed -> "RED"
                MasteryYellow -> "YELLOW"
                MasteryGreen -> "GREEN"
                else -> "UNKNOWN"
            }
        assertEquals(expected, actual)
    }

    @Test
    @DisplayName("三档色值互不相同")
    fun threeLevelsAreDistinct() {
        val colors: Set<Color> = setOf(MasteryRed, MasteryYellow, MasteryGreen)
        assertEquals(3, colors.size)
    }
}
