package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 裸结构化 LaTeX 补定界符。
 *
 * 对应真机反馈「分数别用横杠，要分子在上分母在下」：模型写的裸 `\frac{1}{2}`
 * 原来被压成 `1/2`，这里把它包成 `$\\frac{1}{2}$` 交给渲染器。
 */
@DisplayName("裸 LaTeX 补定界符")
class BareLatexTest {
    @Test
    @DisplayName("裸分式被包成行内公式")
    fun `裸分式`() {
        assertEquals("面积是 \$\\frac{1}{2}\$ab。", BareLatex.wrap("面积是 \\frac{1}{2}ab。"))
    }

    @Test
    @DisplayName("dfrac 与 tfrac 同等处理")
    fun `其它分式命令`() {
        assertEquals("$\\dfrac{3}{4}$", BareLatex.wrap("\\dfrac{3}{4}"))
        assertEquals("$\\tfrac{3}{4}$", BareLatex.wrap("\\tfrac{3}{4}"))
    }

    @Test
    @DisplayName("根号也走排版")
    fun `根号`() {
        assertEquals("$\\sqrt{2}$", BareLatex.wrap("\\sqrt{2}"))
        assertEquals("$\\sqrt[3]{8}$", BareLatex.wrap("\\sqrt[3]{8}"))
    }

    @Test
    @DisplayName("已经在数学环境里的内容不再包一层")
    fun `不重复包裹`() {
        assertEquals("已知 $\\frac{1}{2}$，求 \$x\$。", BareLatex.wrap("已知 $\\frac{1}{2}$，求 \$x\$。"))
    }

    @Test
    @DisplayName("花括号不配对时原样保留")
    fun `不完整不处理`() {
        assertEquals("这是 \\frac{1 没写完", BareLatex.wrap("这是 \\frac{1 没写完"))
    }

    @Test
    @DisplayName("非结构性命令不动（交给 LatexPlain 换符号）")
    fun `符号命令不碰`() {
        assertEquals("H_2SO_4 \\cdot xSO_3", BareLatex.wrap("H_2SO_4 \\cdot xSO_3"))
    }

    @Test
    @DisplayName("嵌套花括号按配对结束位置截断")
    fun `嵌套花括号`() {
        assertEquals("$\\frac{a_{1}}{b_{2}}$ 与后文", BareLatex.wrap("\\frac{a_{1}}{b_{2}} 与后文"))
    }

    @Test
    @DisplayName("一句话里多个裸分式都被包上")
    fun `多个分式`() {
        assertEquals("$\\frac{1}{2}$ 和 $\\frac{3}{4}$", BareLatex.wrap("\\frac{1}{2} 和 \\frac{3}{4}"))
    }

    @Test
    @DisplayName("不带花括号的单字符参数也能包成公式")
    fun `裸参数`() {
        // `\sqrt2`、`\frac12` 是模型省括号的常见写法：以前判「不完整」，只能退化成 √(2)
        assertEquals("$\\sqrt2$", BareLatex.wrap("\\sqrt2"))
        assertEquals("$\\frac12$", BareLatex.wrap("\\frac12"))
    }

    @Test
    @DisplayName("中文不当参数，命令原样保留")
    fun `中文参数`() {
        assertEquals("\\frac 面积", BareLatex.wrap("\\frac 面积"))
    }

    @Test
    @DisplayName("没有反斜杠的纯文本原样返回")
    fun `纯文本`() {
        val text = "把 x 移到右边，两边同除以 2。"
        assertEquals(text, BareLatex.wrap(text))
    }
}
