package com.totop.app.ui.chat

import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 引导模式的展示策略与提示生成。
 *
 * 这两组规则直接对应 FR-2 的硬约束：
 * 「GUIDED 严禁提前渲染 finalAnswer」「SOCRATIC 只渲染提问与方向」，
 * 因此必须有断言把它们钉死，任何改动都要先改测试。
 */
class GuidePolicyTest {
    @Test
    @DisplayName("DIRECT 永远展示答案")
    fun directShowsAnswer() {
        assertTrue(GuidePolicy.showsFinalAnswer(TeachingMode.DIRECT, revealed = false))
        assertTrue(GuidePolicy.showsFinalAnswer(TeachingMode.DIRECT, revealed = true))
        assertTrue(GuidePolicy.showsSolutionSteps(TeachingMode.DIRECT))
    }

    @Test
    @DisplayName("GUIDED 未看全解前绝不展示答案")
    fun guidedHidesAnswerUntilRevealed() {
        assertFalse(GuidePolicy.showsFinalAnswer(TeachingMode.GUIDED, revealed = false))
        assertTrue(GuidePolicy.showsFinalAnswer(TeachingMode.GUIDED, revealed = true))
        assertTrue(GuidePolicy.showsSolutionSteps(TeachingMode.GUIDED))
        assertTrue(GuidePolicy.showsRevealAction(TeachingMode.GUIDED, revealed = false))
        assertFalse(GuidePolicy.showsRevealAction(TeachingMode.GUIDED, revealed = true))
    }

    @Test
    @DisplayName("SOCRATIC 不展示步骤与答案")
    fun socraticHidesEverything() {
        assertFalse(GuidePolicy.showsFinalAnswer(TeachingMode.SOCRATIC, revealed = true))
        assertFalse(GuidePolicy.showsSolutionSteps(TeachingMode.SOCRATIC))
        assertFalse(GuidePolicy.showsHints(TeachingMode.SOCRATIC))
        assertTrue(GuidePolicy.showsGuidingOnly(TeachingMode.SOCRATIC))
    }

    @Test
    @DisplayName("提示三级且永不包含最终答案")
    fun hintsNeverLeakFinalAnswer() {
        val hints =
            HintBuilder.build(
                method = "导数法",
                stepContents = listOf("求导得到 f'(x)", "令导数为 0 求极值", "代回原式得答案"),
            )
        assertEquals(3, hints.size)
        assertEquals(listOf(1, 2, 3), hints.map { it.level })
        hints.forEach { hint ->
            assertFalse(hint.text.contains("f(0)=3"), "提示不得包含最终答案")
        }
        assertTrue(hints[0].text.contains("导数法"))
    }

    @Test
    @DisplayName("步骤不足时提示级数相应减少")
    fun hintsShrinkWithFewSteps() {
        val hints = HintBuilder.build(method = "", stepContents = listOf("只有一步"))
        assertEquals(2, hints.size)
        assertTrue(hints[0].text.contains("先把题目条件"))
        assertNull(HintBuilder.levelOf(hints, 3))
    }

    @Test
    @DisplayName("模式元信息覆盖三档")
    fun modeMetaCoversAll() {
        TeachingMode.entries.forEach { mode ->
            assertTrue(ModeMeta.titleOf(mode).isNotBlank())
            assertTrue(ModeMeta.descriptionOf(mode).isNotBlank())
        }
    }
}

/** [KpointCandidates] 的筛选规则。 */
class KpointCandidatesTest {
    private fun point(
        id: String,
        name: String,
        chapter: String,
        order: Int,
        parent: String? = "ch1",
    ): KnowledgePoint =
        KnowledgePoint(
            id = id,
            name = name,
            subject = "math",
            parentId = parent,
            chapter = chapter,
            orderIndex = order,
        )

    @Test
    @DisplayName("空输入返回空候选")
    fun emptyInput() {
        assertTrue(KpointCandidates.pick(emptyList(), "任意题干").isEmpty())
    }

    @Test
    @DisplayName("名字命中题干的知识点排在最前")
    fun nameMatchFirst() {
        val points =
            listOf(
                point("KP_MATH_0001", "导数与单调性", "导数", 1),
                point("KP_MATH_0002", "三角函数图象", "三角", 2),
                point("KP_MATH_0003", "等差数列", "数列", 3),
            )
        val picked = KpointCandidates.pick(points, "求函数在区间上的导数与单调性")
        assertEquals("KP_MATH_0001", picked.first().id)
    }

    @Test
    @DisplayName("长名知识点优先于短名")
    fun longerNameFirst() {
        val points =
            listOf(
                point("KP_MATH_0001", "函数", "函数", 1),
                point("KP_MATH_0002", "函数单调性", "函数", 2),
            )
        val picked = KpointCandidates.pick(points, "考查函数单调性的判定")
        assertEquals("KP_MATH_0002", picked.first().id)
    }

    @Test
    @DisplayName("候选上限生效且 ID 去重")
    fun limitAndDedup() {
        val points =
            (1..80).map { index ->
                point("KP_MATH_%04d".format(index), "知识点" + index, "章节" + (index % 5), index)
            }
        val picked = KpointCandidates.pick(points, "没有任何命中的题干", limit = 10)
        assertEquals(10, picked.size)
        assertEquals(10, picked.map { it.id }.toSet().size)
    }

    @Test
    @DisplayName("同章节兄弟节点跟随命中项出现")
    fun siblingFollowsMatch() {
        val points =
            listOf(
                point("KP_MATH_0001", "椭圆方程", "圆锥曲线", 1),
                point("KP_MATH_0002", "双曲线渐近线", "圆锥曲线", 2),
                point("KP_MATH_0003", "概率加法公式", "概率", 3),
            )
        val picked = KpointCandidates.pick(points, "求椭圆方程")
        assertTrue(picked.map { it.id }.contains("KP_MATH_0002"))
    }
}
