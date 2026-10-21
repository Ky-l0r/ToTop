package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [FormulaParser] 的单元测试。
 *
 * 覆盖点：
 * - 四种定界符（\\( \\) / \\[ \\] / $ / $$）都能切分；
 * - 未闭合、空内容、货币符号等边界不会吞掉后文；
 * - 纯文本走快速路径（不创建 WebView）。
 */
class FormulaParserTest {
    @Test
    @DisplayName("纯文本不走公式路径")
    fun plainText() {
        val parsed = FormulaParser.parse("已知 x 为实数，求最小值")
        assertTrue(parsed is FormulaContent.PlainText)
        assertFalse(FormulaParser.containsDelimiter("已知 x 为实数"))
    }

    @Test
    @DisplayName("行内美元定界符被解析为公式片段")
    fun inlineDollar() {
        val parsed = FormulaParser.parse("当 ${'$'}x=1${'$'} 时成立")
        assertTrue(parsed is FormulaContent.Pieces)
        val pieces = (parsed as FormulaContent.Pieces).pieces
        assertEquals(3, pieces.size)
        assertEquals("当 ", (pieces[0] as FormulaContent.Piece.Text).value)
        assertEquals("x=1", (pieces[1] as FormulaContent.Piece.Formula).latex)
        assertFalse((pieces[1] as FormulaContent.Piece.Formula).displayMode)
        assertEquals(" 时成立", (pieces[2] as FormulaContent.Piece.Text).value)
    }

    @Test
    @DisplayName("双美元定界符为行间公式")
    fun displayDollar() {
        val parsed = FormulaParser.parse("${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}")
        val pieces = (parsed as FormulaContent.Pieces).pieces
        val formula = pieces.single() as FormulaContent.Piece.Formula
        assertEquals("\\frac{1}{2}", formula.latex)
        assertTrue(formula.displayMode)
        assertTrue(FormulaParser.hasDisplayFormula("${'$'}${'$'}x^2${'$'}${'$'}"))
    }

    @Test
    @DisplayName("反斜杠括号与方括号定界符")
    fun escapedDelimiters() {
        val inline = FormulaParser.parse("\\(a+b\\)")
        val inlineFormula = (inline as FormulaContent.Pieces).pieces.single() as FormulaContent.Piece.Formula
        assertEquals("a+b", inlineFormula.latex)
        assertFalse(inlineFormula.displayMode)

        val display = FormulaParser.parse("\\[a+b\\]")
        val displayFormula = (display as FormulaContent.Pieces).pieces.single() as FormulaContent.Piece.Formula
        assertTrue(displayFormula.displayMode)
    }

    @Test
    @DisplayName("未闭合的定界符不吞掉后文")
    fun unclosedDelimiter() {
        val raw = "价格为 3${'$'} 且还剩 5 元"
        val parsed = FormulaParser.parse(raw)
        assertTrue(parsed is FormulaContent.PlainText)
        assertEquals(raw, (parsed as FormulaContent.PlainText).text)
    }

    @Test
    @DisplayName("空公式按普通文字处理")
    fun emptyFormula() {
        val parsed = FormulaParser.parse("空 ${'$'}${'$'} 公式")
        assertTrue(parsed is FormulaContent.PlainText)
    }

    @Test
    @DisplayName("去除定界符但保留内容")
    fun strip() {
        assertEquals("面积是 S=a*b", FormulaParser.stripDelimiters("面积是 ${'$'}S=a*b${'$'}"))
    }

    @Test
    @DisplayName("多段公式混排保持顺序")
    fun multiplePieces() {
        val parsed = FormulaParser.parse("${'$'}a${'$'} 与 ${'$'}b${'$'} 相加")
        val pieces = (parsed as FormulaContent.Pieces).pieces
        assertEquals(4, pieces.size)
        assertEquals("a", (pieces[0] as FormulaContent.Piece.Formula).latex)
        assertEquals(" 与 ", (pieces[1] as FormulaContent.Piece.Text).value)
        assertEquals("b", (pieces[2] as FormulaContent.Piece.Formula).latex)
        assertEquals(" 相加", (pieces[3] as FormulaContent.Piece.Text).value)
    }
}
