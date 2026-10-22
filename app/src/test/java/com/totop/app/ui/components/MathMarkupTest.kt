package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 朴素数学记号解析的契约测试。
 *
 * 这些用例直接对应真机反馈「平方啥的，直接显示的 x∧2，不方便查看」：
 * 模型给的文本里 `2^x`、`x^2` 必须能被识别成上标，同时绝不能把正文改坏。
 */
@DisplayName("朴素数学记号解析")
class MathMarkupTest {
    private fun sup(raw: String): List<String> =
        MathMarkup.parse(raw).filterIsInstance<MathMarkup.Segment.Sup>().map { it.value }

    private fun plain(raw: String): String =
        MathMarkup.parse(raw).filterIsInstance<MathMarkup.Segment.Text>().joinToString("") { it.value }

    @Test
    @DisplayName("x^2 被识别为上标，且基线与上标内容都保留")
    fun `识别单个字符上标`() {
        val segments = MathMarkup.parse("f(x)=x^2")
        assertEquals("f(x)=x", plain("f(x)=x^2"))
        assertEquals(listOf("2"), segments.filterIsInstance<MathMarkup.Segment.Sup>().map { it.value })
    }

    @Test
    @DisplayName("2^x 与 2^x+x-5 这类前缀底数也认")
    fun `识别前缀底数的上标`() {
        assertEquals(listOf("x"), sup("2^x=5"))
        // 一句里出现两次上标就要识别两次，顺序与原文一致
        assertEquals(listOf("x", "x"), sup("令 2^x+x-5=x，化简得 2^x=5"))
    }

    @Test
    @DisplayName("中文输入法的 U+2227 同样被当作上标引导符")
    fun `识别中文输入法的与符号`() {
        // 这是真机截图里实际出现的形态：x∧2
        assertEquals(listOf("2"), sup("x\u22272"))
    }

    @Test
    @DisplayName("花括号与圆括号里的多字符都能整体上标")
    fun `识别多字符上标`() {
        assertEquals(listOf("n+1"), sup("x^{n+1}"))
        assertEquals(listOf("n+1"), sup("x^(n+1)"))
    }

    @Test
    @DisplayName("下标同样支持")
    fun `识别下标`() {
        val subs =
            MathMarkup.parse("a_1+a_2")
                .filterIsInstance<MathMarkup.Segment.Sub>()
                .map { it.value }
        assertEquals(listOf("1", "2"), subs)
    }

    @Test
    @DisplayName("引导符后面是空白时原样保留，不吃掉后文")
    fun `空白不构成上标`() {
        assertFalse(MathMarkup.hasScript("a ^ b"))
        assertEquals("a ^ b", MathMarkup.toPlain("a ^ b"))
    }

    @Test
    @DisplayName("引导符在结尾时原样保留")
    fun `结尾引导符不丢失`() {
        assertFalse(MathMarkup.hasScript("和是 3^"))
        assertEquals("和是 3^", MathMarkup.toPlain("和是 3^"))
    }

    @Test
    @DisplayName("花括号不配对时退回单字符，绝不吞掉后文")
    fun `未闭合花括号保守处理`() {
        val plainText = MathMarkup.toPlain("x^{2+3")
        // 只吃 { 这一个字符作为上标内容，后面的 2+3 必须留在正文里
        assertEquals("x{2+3", plainText)
    }

    @Test
    @DisplayName("含美元定界符的文本完全交给 LaTeX 渲染，本解析器不介入")
    fun `美元定界符不处理`() {
        val raw = "\$x^2\$"
        assertFalse(MathMarkup.hasScript(raw))
        assertEquals(raw, MathMarkup.toPlain(raw))
    }

    @Test
    @DisplayName("转义写法 \\^ 当作字面字符，不产生上标")
    fun `转义不产生上标`() {
        assertFalse(MathMarkup.hasScript("按位异或写作 a\\^b"))
        assertTrue(MathMarkup.toPlain("按位异或写作 a\\^b").contains("a^b"))
    }

    @Test
    @DisplayName("toPlain 只去掉引导符与包裹括号，不丢正文")
    fun `片段拼接不丢正文`() {
        val raw = "由 f(x)=2^x+x-5 得 x_0=0，且 g(x^{a+1})=x"
        val plain = MathMarkup.toPlain(raw)
        // 中文字符与数字全部保留，去掉的只是 ^ _ { } 这四个记号字符
        assertEquals("由 f(x)=2x+x-5 得 x0=0，且 g(xa+1)=x", plain)
        // 原文含 2 个 ^、1 个 _、1 对花括号，共 5 个记号字符被去掉
        assertEquals(5, raw.length - plain.length, "恰好去掉 5 个记号字符")
    }

    @Test
    @DisplayName("纯中文文本零开销地判定为无记号")
    fun `纯中文无记号`() {
        assertFalse(MathMarkup.hasScript("先把已知条件列出来，再判断题型"))
    }

    @Test
    @DisplayName("空串不崩")
    fun `空串`() {
        assertFalse(MathMarkup.hasScript(""))
        assertEquals("", MathMarkup.toPlain(""))
    }
}
