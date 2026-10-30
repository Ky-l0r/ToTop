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
 * 这些规则直接对应 FR-2 的硬约束与 0.2.0 的模式合并：
 * 「GUIDED 未揭示前既不展示步骤也不展示答案」，
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
    @DisplayName("GUIDED 未揭示前既不展示步骤也不展示答案")
    fun guidedHidesEverythingUntilRevealed() {
        // 这是 0.2.0 最关键的一条不变式：旧实现 GUIDED 会直接渲染标准步骤，
        // 观感与「直接讲解」没差别，真机反馈因此要求合并两档。
        assertFalse(GuidePolicy.showsFinalAnswer(TeachingMode.GUIDED, revealed = false))
        assertFalse(GuidePolicy.showsSolutionSteps(TeachingMode.GUIDED))
        assertTrue(GuidePolicy.withholdsSolution(TeachingMode.GUIDED, revealed = false))
        assertTrue(GuidePolicy.showsHints(TeachingMode.GUIDED))
        assertTrue(GuidePolicy.showsRevealAction(TeachingMode.GUIDED, revealed = false))
    }

    @Test
    @DisplayName("GUIDED 揭示后步骤与答案同时放开")
    fun guidedRevealsEverything() {
        assertTrue(GuidePolicy.showsFinalAnswer(TeachingMode.GUIDED, revealed = true))
        assertFalse(GuidePolicy.withholdsSolution(TeachingMode.GUIDED, revealed = true))
        assertFalse(GuidePolicy.showsRevealAction(TeachingMode.GUIDED, revealed = true))
    }

    @Test
    @DisplayName("DIRECT 不受揭示状态影响，不展示提示")
    fun directNeverWithholds() {
        assertFalse(GuidePolicy.withholdsSolution(TeachingMode.DIRECT, revealed = false))
        assertFalse(GuidePolicy.showsHints(TeachingMode.DIRECT))
        assertFalse(GuidePolicy.showsRevealAction(TeachingMode.DIRECT, revealed = false))
    }

    @Test
    @DisplayName("模式只剩两档，旧的 SOCRATIC 被归到 GUIDED")
    fun legacySocraticFoldsIntoGuided() {
        assertEquals(listOf("DIRECT", "GUIDED"), TeachingMode.entries.map { it.wire })
        assertEquals(TeachingMode.GUIDED, TeachingMode.fromWire("SOCRATIC"))
        assertEquals(TeachingMode.GUIDED, TeachingMode.fromWire("socratic"))
        assertEquals(TeachingMode.GUIDED, TeachingMode.fromWireOrNull("SOCRATIC"))
        assertTrue(TeachingMode.isLegacy("SOCRATIC"))
        assertFalse(TeachingMode.isLegacy("GUIDED"))
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
        // 没给方法名时用兜底方向，措辞是「先把题目给了什么、要求什么逐条写下来」
        assertTrue(hints[0].text.contains("先把题目给了什么"))
        assertNull(HintBuilder.levelOf(hints, 3))
    }

    @Test
    @DisplayName("第 3 级接着第 1 步往下给，不把已经说过的步骤再列一遍")
    fun thirdHintContinuesInsteadOfRepeating() {
        val hints =
            HintBuilder.build(
                method = "守恒法",
                stepContents = listOf("先写出反应物与产物", "标出各元素化合价的变化", "配平电子转移数", "合并得到配平式"),
            )
        val second = requireNotNull(HintBuilder.levelOf(hints, 2))
        val third = requireNotNull(HintBuilder.levelOf(hints, 3))
        assertTrue(second.text.contains("先写出反应物与产物"))
        // 重复前两步是旧实现最刺眼的问题：第 3 级里不能再出现第 1 步
        assertFalse(third.text.contains("先写出反应物与产物"), "第 3 级不得重复第 2 级已给的内容")
        assertTrue(third.text.contains("标出各元素化合价的变化"))
        assertTrue(third.text.contains("配平电子转移数"))
    }

    @Test
    @DisplayName("提示不泄露最终答案：含答案的分句被擦掉")
    fun hintsScrubAnswerClause() {
        val hints =
            HintBuilder.build(
                method = "元素守恒",
                stepContents =
                    listOf(
                        "先确定溶液里有哪些离子",
                        "按电荷守恒列式，得到 c(Na+)=0.2 mol/L",
                        "因此答案是 0.2 mol/L",
                    ),
                finalAnswer = "0.2 mol/L",
            )
        hints.forEach { hint ->
            assertFalse(hint.text.contains("0.2 mol/L"), "提示不得出现最终答案：" + hint.text)
        }
    }

    @Test
    @DisplayName("模式元信息覆盖全部档位")
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
