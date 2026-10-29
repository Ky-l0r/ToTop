package com.totop.app.ui.solve

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 讲解分行归类（纯逻辑）。
 *
 * 断言的重点不是「分得对不对」这种审美问题，而是三类内容必须能被稳定识别：
 * 思路（为什么）、计算（算式）、结论（答案），它们决定界面上的颜色与分组。
 */
class AnswerOutlineTest {
    @Test
    @DisplayName("编号步骤带出步骤号，且不把小数点数字当编号")
    fun stepNumbers() {
        val lines = AnswerOutline.parse("4. 系统机械能守恒，动能减少量等于电势能增加量。")
        assertEquals(AnswerLineKind.STEP, lines.single().kind)
        assertEquals("4", lines.single().stepNo)
        assertTrue(lines.single().text.startsWith("系统机械能守恒"))

        // 「2.5 kg」是数值，不是第 2 步
        val numeric = AnswerOutline.parse("2.5 kg 的物体从静止开始下落")
        assertEquals(AnswerLineKind.NORMAL, numeric.single().kind)
    }

    @Test
    @DisplayName("「为什么」类行归为思路，并去掉前缀")
    fun reasoningLines() {
        val line = AnswerOutline.parse("为什么：机械能守恒，只有静电力做功。").single()
        assertEquals(AnswerLineKind.REASONING, line.kind)

        val why = AnswerOutline.parse("因为两绳夹角 60°，所以构成等边三角形。").single()
        assertEquals(AnswerLineKind.REASONING, why.kind)
    }

    @Test
    @DisplayName("答案与结论单独归类，便于强调")
    fun conclusionLines() {
        assertEquals(AnswerLineKind.CONCLUSION, AnswerOutline.parse("答案：C").single().kind)
        assertEquals(AnswerLineKind.CONCLUSION, AnswerOutline.parse("综上，本题选 C。").single().kind)
    }

    @Test
    @DisplayName("【…】与「解题思路」这类整行标题")
    fun headings() {
        assertEquals(AnswerLineKind.HEADING, AnswerOutline.parse("【标准解答】").single().kind)
        assertEquals(AnswerLineKind.HEADING, AnswerOutline.parse("解题思路：").single().kind)
        assertEquals("标准解答", AnswerOutline.parse("【标准解答】").single().text)
    }

    @Test
    @DisplayName("算式行与叙述行分开：中文多的算叙述")
    fun calculationVersusProse() {
        val formula = AnswerOutline.parse("E_{k0}=\\frac{1}{2}mv_0^2").single()
        assertEquals(AnswerLineKind.CALCULATION, formula.kind)

        val prose = AnswerOutline.parse("由上式可见，小球的速度随时间均匀增大，这与我们的直觉一致，也说明前面的受力分析没有错。")
        assertEquals(AnswerLineKind.NORMAL, prose.single().kind)
    }

    @Test
    @DisplayName("Markdown 残留被清掉，空行不产生空块")
    fun normalizeAndSkipBlank() {
        assertEquals("重点", AnswerOutline.normalize("## **重点**"))
        val lines = AnswerOutline.parse("第一段\n\n\n第二段")
        assertEquals(2, lines.size)
        assertEquals("第一段", lines.first().text)
    }
}
