package com.totop.app.ui.reviewmaterial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 复习资料的文本处理。
 *
 * 这两件事都会直接印在 A4 上，错了就是「看得见」的错误：
 * 句尾多一个星号、化学式的数字站在基线上。
 */
@DisplayName("复习资料文本处理")
class ReviewMaterialTextTest {
    @Test
    @DisplayName("成对加粗保留，加粗之外没有星号")
    fun `加粗切分`() {
        val runs = ReviewText.splitBold("注意**先定定义域**再讨论")
        assertEquals(listOf("注意", "先定定义域", "再讨论"), runs.map { it.text })
        assertEquals(listOf(false, true, false), runs.map { it.bold })
    }

    @Test
    @DisplayName("句尾多出来的星号被抹掉")
    fun `多余星号`() {
        val runs = ReviewText.splitBold("ΔG=ΔH−TΔS<0才自发*")
        assertEquals("ΔG=ΔH−TΔS<0才自发", runs.joinToString("") { it.text })
    }

    @Test
    @DisplayName("单星号强调不会漏到纸上")
    fun `单星号`() {
        val runs = ReviewText.splitBold("*重点* 与 **难点**")
        val text = runs.joinToString("") { it.text }
        assertFalse(text.contains("*"))
        assertEquals("重点 与 难点", text)
    }

    @Test
    @DisplayName("化学式数字转下标")
    fun `化学式下标`() {
        assertEquals("C₈H₁₆O₄", ReviewText.formatChemical("C8H16O4"))
        assertEquals("H₂SO₄", ReviewText.formatChemical("H2SO4"))
        assertEquals("CO₂", ReviewText.formatChemical("CO2"))
        assertEquals("O₂", ReviewText.formatChemical("O2"))
        assertEquals("2H₂ + O₂ → 2H₂O", ReviewText.formatChemical("2H2 + O2 → 2H2O"))
    }

    @Test
    @DisplayName("不是化学式的编号保持原样")
    fun `非化学式不误转`() {
        assertEquals("A4 纸", ReviewText.formatChemical("A4 纸"))
        assertEquals("MP3", ReviewText.formatChemical("MP3"))
        assertEquals("P2P", ReviewText.formatChemical("P2P"))
        assertEquals("K12", ReviewText.formatChemical("K12"))
        assertEquals("第 2 题", ReviewText.formatChemical("第 2 题"))
    }

    @Test
    @DisplayName("答案行识别")
    fun `答案行`() {
        assertTrue(ReviewText.isAnswerLine("答案：x=2"))
        assertTrue(ReviewText.isAnswerLine("  答案：2 mol"))
        assertFalse(ReviewText.isAnswerLine("思路：先求导"))
    }

    @Test
    @DisplayName("例题样式能被解析出来")
    fun `例题样式`() {
        assertEquals(ReviewBlockStyle.EXAMPLE, ReviewBlockStyle.parse("example"))
        assertEquals(ReviewBlockStyle.EXAMPLE, ReviewBlockStyle.parse("Exercise"))
        assertEquals(ReviewBlockStyle.PARAGRAPH, ReviewBlockStyle.parse("unknown"))
    }
}
