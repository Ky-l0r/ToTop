package com.totop.app.ui.reviewmaterial

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 复习资料 PDF 的标题块几何。
 *
 * 这条不变式来自一次真机反馈：导出的 PDF 里章节标题、小节标题与正文叠在一起。
 * 根因不是字号或字体，而是**游标推进量不足**——小节标题画完后只往下挪了 5pt，
 * 下一段正文的第一行几乎落在同一条基线上，看起来就是两行字糊成一团。
 *
 * 因此这里守的不是某个具体数值，而是「标题画完之后，下一个块的顶部必须低于整行字高」。
 */
@DisplayName("复习资料 PDF 标题几何")
class ReviewPdfFlowTest {
    @Test
    @DisplayName("基线落在块顶下方，字顶不会溢到上一块")
    fun `基线下移`() {
        val top = 100f
        val baseline = ReviewPdfFlow.baselineOf(top, 13f)
        assertTrue(baseline > top, "基线必须低于块顶，实际：" + baseline)
        assertEquals(top + 13f * ReviewPdfFlow.GLYPH_RISE, baseline, 0.001f)
    }

    @Test
    @DisplayName("标题之后的下一个块顶至少让出一整行字高")
    fun `推进量足够`() {
        // 覆盖正文、小节、章节三种字号
        listOf(11.5f, 13f, 16f, 20f).forEach { size ->
            listOf(0f, 5f, 13f).forEach { after ->
                val baseline = ReviewPdfFlow.baselineOf(0f, size)
                val next = ReviewPdfFlow.nextTopAfter(baseline, size, after)
                assertTrue(
                    next - baseline >= size,
                    "字号 " + size + "、段后 " + after + " 时净空不足，下一段会压到标题上",
                )
            }
        }
    }

    @Test
    @DisplayName("段后间距越大，下一块越靠下")
    fun `单调`() {
        val baseline = ReviewPdfFlow.baselineOf(0f, 13f)
        val small = ReviewPdfFlow.nextTopAfter(baseline, 13f, 5f)
        val large = ReviewPdfFlow.nextTopAfter(baseline, 13f, 20f)
        assertTrue(large > small)
    }
}
