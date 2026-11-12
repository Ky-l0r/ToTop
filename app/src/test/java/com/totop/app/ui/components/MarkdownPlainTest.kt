package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Markdown 记号归一化的契约测试。
 *
 * 对应真机反馈「陪伴回答里偶尔出现两个星号包裹强调内容」：`companion_ask.md` 只禁了
 * Markdown 表格，没禁加粗，模型写的 `**关键**` 就原样漏在屏幕上。
 */
@DisplayName("Markdown 记号归一化")
class MarkdownPlainTest {
    private fun normalize(raw: String): String = MarkdownPlain.normalize(raw)

    @Test
    @DisplayName("成对加粗记号被去掉，文字保留")
    fun `成对加粗`() {
        assertEquals("速度是矢量", normalize("**速度**是矢量"))
        assertEquals("先看定义域再求导", normalize("先看__定义域__再求导"))
        // 中文紧邻记号是常态：不能因为后面是汉字就当成「不是加粗」
        assertEquals("关键：先配方", normalize("**关键**：先配方"))
    }

    @Test
    @DisplayName("不闭合的记号原样保留（流式输出中途）")
    fun `不闭合`() {
        // 增量流里 `**` 会先到、后到：此时必须原样显示，否则星号会闪一下又消失
        assertEquals("这是**关键", normalize("这是**关键"))
        assertEquals("**", normalize("**"))
    }

    @Test
    @DisplayName("幂运算的星号不能当成加粗")
    fun `幂运算`() {
        assertEquals("2**3=8", normalize("2**3=8"))
        assertEquals("a**b**c", normalize("a**b**c"))
    }

    @Test
    @DisplayName("行首列表与标题记号被压平")
    fun `行首记号`() {
        assertEquals("· 第一点", normalize("- 第一点"))
        assertEquals("· 第二点", normalize("* 第二点"))
        assertEquals("小标题", normalize("### 小标题"))
        // 行内的减号不动：中文里的破折号与编号常用它
        assertEquals("区间是 1-2 之间", normalize("区间是 1-2 之间"))
    }

    @Test
    @DisplayName("没有记号时逐字返回，不产生任何改动")
    fun `无记号`() {
        val text = "把 x 移到右边，两边同除以 2。"
        assertEquals(text, normalize(text))
        assertEquals("", normalize(""))
    }
}
