package com.totop.app.ui.mistakes

import com.totop.app.domain.model.ErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 错题本筛选、格式化与分页合并的纯逻辑。
 *
 * 这些规则直接对应 FR-4.1（筛选/搜索）与 FR-4.5（复发提示），
 * 改任何一处都要先改这里的断言。
 */
class MistakeFilterTest {
    @Test
    @DisplayName("LIKE 转义：% 与 _ 不再当作通配符")
    fun escapeLikeHandlesWildcards() {
        assertEquals(BACKSLASH + "%", MistakeFilter.escapeLike("%"))
        assertEquals("100" + BACKSLASH + "%", MistakeFilter.escapeLike("100%"))
        assertEquals("a" + BACKSLASH + "_b", MistakeFilter.escapeLike("a_b"))
        // 反斜杠自身先转义，再转义通配符，避免 ` 与 % 拼出注入
        assertEquals("a" + BACKSLASH + BACKSLASH + "b", MistakeFilter.escapeLike("a" + BACKSLASH + "b"))
        assertEquals("x" + BACKSLASH + BACKSLASH + BACKSLASH + "%", MistakeFilter.escapeLike("x" + BACKSLASH + "%"))
    }

    @Test
    @DisplayName("筛选生效判定与计数")
    fun filterActiveState() {
        assertFalse(MistakeFilter().isActive)
        assertEquals(0, MistakeFilter().activeCount)

        val filter =
            MistakeFilter(
                subject = "math",
                errorType = ErrorType.CALCULATION_ERROR,
                timeWindow = TimeWindow.WEEK,
                keyword = "  导数  ",
            )
        assertTrue(filter.isActive)
        assertEquals(4, filter.activeCount)
        assertEquals("导数", filter.normalizedKeyword)

        // 只有空白的关键词不算筛选生效
        assertFalse(MistakeFilter(keyword = "   ").isActive)
        assertTrue(MistakeFilter().cleared().activeCount == 0)
    }

    @Test
    @DisplayName("时间窗口下界：ALL 不加时间条件")
    fun timeWindowBounds() {
        val now = 1_700_000_000_000L
        assertNull(TimeWindow.ALL.fromUtcOrNull(now))
        assertEquals(now - 7L * DAY, TimeWindow.WEEK.fromUtcOrNull(now))
        assertEquals(now - 30L * DAY, TimeWindow.MONTH.fromUtcOrNull(now))
        assertEquals(now - 90L * DAY, TimeWindow.QUARTER.fromUtcOrNull(now))
    }

    @Test
    @DisplayName("复发徽标：首次不显示，第 2 次起显示「第 N 次错」")
    fun recurrenceBadge() {
        assertNull(recurrenceBadgeText(0))
        assertNull(recurrenceBadgeText(1))
        assertEquals("第 2 次错", recurrenceBadgeText(2))
        assertEquals("第 11 次错", recurrenceBadgeText(11))
    }

    @Test
    @DisplayName("题干摘要：压缩空白、按字数截断并加省略号")
    fun summarizeText() {
        assertEquals("", summarize("", 10))
        assertEquals("abc", summarize("  abc  ", 10))
        assertEquals("a b", summarize("a   b", 10))
        assertEquals("abcde…", summarize("abcdefg", 5))
        assertEquals("", summarize("abc", 0))
    }

    @Test
    @DisplayName("学科代码转中文，未知学科原样保留")
    fun subjectLabels() {
        assertEquals("数学", subjectLabelOf("math"))
        assertEquals("物理", subjectLabelOf("Physics"))
        assertEquals("未标注学科", subjectLabelOf(" "))
        assertEquals("geo", subjectLabelOf("geo"))
    }

    @Test
    @DisplayName("卡片的知识点标签最多三个，其余折叠成 +N")
    fun kpointTagsCollapse() {
        assertEquals(listOf("A", "B", "C", "+2"), card(kpointNames = listOf("A", "B", "C", "D", "E")).kpointTags)
        assertEquals(listOf("A", "B"), card(kpointNames = listOf("A", "B")).kpointTags)
        assertEquals(listOf("A", "B", "C"), card(kpointNames = listOf("A", "B", "C")).kpointTags)
    }

    @Test
    @DisplayName("分页合并去重：翻页期间新写入的行不会重复出现")
    fun mergeDistinctDeduplicates() {
        val first = listOf(card("m1"), card("m2"))
        val second = listOf(card("m2"), card("m3"))
        val merged = MistakesViewModel.mergeDistinct(first, second)
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.mistakeId })
        assertEquals(first, MistakesViewModel.mergeDistinct(first, emptyList()))
    }

    @Test
    @DisplayName("空态文案：有筛选时提示放宽条件，无筛选时提示去解题")
    fun emptyStateMessage() {
        val filtered = MistakesUiState(filter = MistakeFilter(subject = "math"), loading = false)
        assertTrue(filtered.emptyMessage.contains("筛选"))

        val plain = MistakesUiState(loading = false)
        assertTrue(plain.emptyMessage.contains("解题"))
    }

    @Test
    @DisplayName("计数文案：加载中 / 空 / 部分加载")
    fun countText() {
        assertEquals("正在读取本机错题…", MistakesUiState(loading = true).countText)
        assertEquals("共 0 道", MistakesUiState(loading = false, total = 0).countText)
        val partial = MistakesUiState(loading = false, total = 50, items = List(20) { card("m" + it) })
        assertTrue(partial.countText.contains("共 50 道"))
        assertTrue(partial.countText.contains("已加载 20 道"))
        assertTrue(partial.hasMore)
    }

    private fun card(
        id: String = "m1",
        kpointNames: List<String> = emptyList(),
    ): MistakeCardUi =
        MistakeCardUi(
            mistakeId = id,
            questionId = "q-" + id,
            stemText = "题干 " + id,
            subject = "math",
            errorTypeLabel = ErrorType.CALCULATION_ERROR.label,
            errorTypeCode = ErrorType.CALCULATION_ERROR.code,
            kpointNames = kpointNames,
            recurrenceIndex = 1,
            createdAtUtc = 0L,
            imagePath = null,
            thumbnailPath = null,
            finalAnswer = "1",
            hasLocatedErrorStep = true,
        )

    private companion object {
        const val DAY: Long = 86_400_000L

        /** 反斜杠字符：用码位构造，避免测试源码里的多重转义把断言写错。 */
        val BACKSLASH: String = 92.toChar().toString()
    }
}
