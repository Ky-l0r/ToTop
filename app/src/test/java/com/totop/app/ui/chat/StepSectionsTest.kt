package com.totop.app.ui.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 小问切段与「提示从卡住那一问起」的定位规则。
 *
 * 这两件事共用同一份切段结果：界面上分成「第 (1) 问 / 第 (2) 问」，
 * 提示也从学生卡住的那一问开始给。规则一旦错位，
 * 学生说「我不会第 (3) 问」却收到「先配方」的提示，等于没听懂问题。
 */
class StepSectionsTest {
    private fun step(
        idx: Int,
        content: String,
    ): SolutionStepView = SolutionStepView(idx = idx, content = content, why = "")

    @Test
    @DisplayName("没有小问标记时整份步骤只有一段，且不带标题")
    fun singleSectionWithoutMarker() {
        val steps =
            listOf(
                step(1, "列出已知条件"),
                step(2, "把 x 移到左边"),
                step(3, "解得 x = 2"),
            )
        val sections = StepSections.group(steps)
        assertEquals(1, sections.size)
        assertEquals("", sections.first().title)
        assertEquals(3, sections.first().steps.size)
    }

    @Test
    @DisplayName("(1)(2) 标记把步骤切成两段，标题为「第 N 问」")
    fun splitsByParenMarker() {
        val steps =
            listOf(
                step(1, "(1) 先求抛物线的解析式"),
                step(2, "(1) 代点得 a = 1"),
                step(3, "(2) 设 A 点坐标"),
                step(4, "(2) 由 AB 与 x 轴平行得纵坐标相等"),
            )
        val sections = StepSections.group(steps)
        assertEquals(2, sections.size)
        assertEquals("第 1 问", sections[0].title)
        assertEquals(2, sections[0].steps.size)
        assertEquals("第 2 问", sections[1].title)
        assertEquals(2, sections[1].steps.size)
    }

    @Test
    @DisplayName("全角括号与「第2问」写法同样识别")
    fun recognizesFullWidthAndChineseMarker() {
        assertEquals(2, StepSections.markerOf("（2）设未知数"))
        assertEquals(3, StepSections.markerOf("第3问：求面积"))
        assertNull(StepSections.markerOf("由(1)得 a = 1"))
    }

    @Test
    @DisplayName("「由(1)得」这类行内引用不会开新段")
    fun inlineReferenceDoesNotSplit() {
        val steps =
            listOf(
                step(1, "(1) 求解析式得 y = x + 1"),
                step(2, "由(1)得 y 的表达式，代入第二式"),
                step(3, "(2) 解方程组"),
            )
        val sections = StepSections.group(steps)
        assertEquals(2, sections.size)
        assertEquals(2, sections[0].steps.size)
        assertEquals("第 2 问", sections[1].title)
    }

    @Test
    @DisplayName("卡点在第 2 问时提示从第 2 问的第一步起")
    fun startIndexFollowsFailingSubQuestion() {
        val steps =
            listOf(
                step(1, "(1) 求解析式"),
                step(2, "(1) 代点求解"),
                step(3, "(2) 设 A 点坐标"),
                step(4, "(2) 联立方程"),
            )
        // 学生卡在第 3 步（第 2 问的开头）
        assertEquals(2, StepSections.startIndexOf(steps, 3))
        // 学生卡在第 1 问内部时仍从 0 起
        assertEquals(0, StepSections.startIndexOf(steps, 2))
    }

    @Test
    @DisplayName("没有错因、越界或整题无小问时退回从第一步起")
    fun startIndexFallsBackToZero() {
        val marked =
            listOf(
                step(1, "(1) 求解析式"),
                step(2, "(2) 设坐标"),
            )
        assertEquals(0, StepSections.startIndexOf(marked, null))
        assertEquals(0, StepSections.startIndexOf(marked, 0))
        assertEquals(0, StepSections.startIndexOf(marked, 99))

        val plain =
            listOf(
                step(1, "列式"),
                step(2, "求解"),
            )
        // 整道题只有一段、没有小问标记：谈不上「从第几问起」
        assertEquals(0, StepSections.startIndexOf(plain, 2))
        assertEquals(0, StepSections.startIndexOf(emptyList(), 1))
    }
}
