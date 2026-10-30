package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 裸 LaTeX 命令归一化的契约测试。
 *
 * 对应真机反馈「化学式渲染异常」：陪伴页回答里出现
 * `H_2SO_4 \cdot xSO_3`，屏幕上是带反斜杠的原文。
 */
@DisplayName("裸 LaTeX 命令归一化")
class LatexPlainTest {
    private fun normalize(raw: String): String = LatexPlain.normalize(raw)

    @Test
    @DisplayName("没有反斜杠的正文原样返回")
    fun `纯中文不动`() {
        val text = "它比浓硫酸更狠，因为里面溶了游离的 SO_3。"
        assertEquals(text, normalize(text))
    }

    @Test
    @DisplayName("符号命令换成 Unicode 字符")
    fun `符号命令`() {
        assertEquals("H_2SO_4 · xSO_3", normalize("H_2SO_4 \\cdot xSO_3"))
        assertEquals("a × b", normalize("a \\times b"))
        assertEquals("x ≤ 3 且 x ≥ 1", normalize("x \\le 3 且 x \\ge 1"))
        assertEquals("A → B ⇌ C", normalize("A \\rightarrow B \\rightleftharpoons C"))
        // 命令名后面本来就有一个空格，原样保留：`\Delta H` 读作「Δ H」不会被连成一个词
        assertEquals("Δ H < 0", normalize("\\Delta H < 0"))
    }

    @Test
    @DisplayName("分式与根式换成可读写法")
    fun `分式根式`() {
        assertEquals("s/t", normalize("\\frac{s}{t}"))
        assertEquals("(a+b)/(2c)", normalize("\\frac{a+b}{2c}"))
        assertEquals("√(x+1)", normalize("\\sqrt{x+1}"))
    }

    @Test
    @DisplayName("只取内容的命令剥掉外壳")
    fun `文本包装命令`() {
        assertEquals("NaCl", normalize("\\mathrm{NaCl}"))
        assertEquals("溶液呈中性", normalize("\\text{溶液呈中性}"))
        assertEquals("H2SO4", normalize("\\ce{H2SO4}"))
    }

    @Test
    @DisplayName("间距命令压成一个空格，标点转义还原")
    fun `间距与转义`() {
        assertEquals("a b", normalize("a\\,b"))
        assertEquals("50%", normalize("50\\%"))
        assertEquals("a b", normalize("a\\quad b"))
    }

    @Test
    @DisplayName("认不出的命令原样保留，不吞字符")
    fun `未知命令保留`() {
        val text = "见 \\unknowncmd{x} 处"
        val out = normalize(text)
        assertEquals(text, out, "未知命令必须逐字保留，不能被吃掉反斜杠或命令名")
    }

    @Test
    @DisplayName("路径里的反斜杠不被当成命令")
    fun `路径不误伤`() {
        val text = "文件在 C:\\Users\\demo 下"
        assertTrue(normalize(text).contains("C:\\Users\\demo"))
    }
}
