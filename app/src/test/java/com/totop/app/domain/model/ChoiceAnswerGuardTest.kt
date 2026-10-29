package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 题型与答案个数校验（纯逻辑）。
 *
 * 这组断言对着真实错例写：
 * 1. 单选题被答成「A、C」——最常见的多选化；
 * 2. 多选题只答一个字母——错题本里存下一个不完整的答案；
 * 3. 化学式里的字母（NaCl、AgNO3）不能被当成选项。
 */
class ChoiceAnswerGuardTest {
    private val fourOptions = listOf("A. 铜丝", "B. 蔗糖溶液", "C. 氯化钠固体", "D. 酒精")

    @Test
    @DisplayName("题型归一：中英文与各种写法都收敛到四个受控值")
    fun normalizeVariants() {
        assertEquals(ChoiceAnswerGuard.SINGLE, ChoiceAnswerGuard.normalize("single"))
        assertEquals(ChoiceAnswerGuard.SINGLE, ChoiceAnswerGuard.normalize("单选题"))
        assertEquals(ChoiceAnswerGuard.SINGLE, ChoiceAnswerGuard.normalize("Single-Choice"))
        assertEquals(ChoiceAnswerGuard.MULTI, ChoiceAnswerGuard.normalize("multi"))
        assertEquals(ChoiceAnswerGuard.MULTI, ChoiceAnswerGuard.normalize("多选题"))
        assertEquals(ChoiceAnswerGuard.NONE, ChoiceAnswerGuard.normalize("none"))
        assertEquals(ChoiceAnswerGuard.UNKNOWN, ChoiceAnswerGuard.normalize(null))
        assertEquals(ChoiceAnswerGuard.UNKNOWN, ChoiceAnswerGuard.normalize("随便写的"))
    }

    @Test
    @DisplayName("选项字母提取：只认真正的选项标记")
    fun lettersOfAnswers() {
        assertEquals(listOf('A'), ChoiceAnswerGuard.lettersOf("A"))
        assertEquals(listOf('A', 'C'), ChoiceAnswerGuard.lettersOf("AC"))
        assertEquals(listOf('A', 'C'), ChoiceAnswerGuard.lettersOf("A、C"))
        assertEquals(listOf('A', 'C'), ChoiceAnswerGuard.lettersOf("A,C"))
        assertEquals(listOf('A'), ChoiceAnswerGuard.lettersOf("A. 铜丝"))
        assertEquals(listOf('C'), ChoiceAnswerGuard.lettersOf("C. 氯化钠固体"))
        assertEquals(listOf('B', 'D'), ChoiceAnswerGuard.lettersOf("B、D"))
        // 化学式与单位里的字母不是选项
        assertTrue(ChoiceAnswerGuard.lettersOf("NaCl 溶液").isEmpty())
        assertTrue(ChoiceAnswerGuard.lettersOf("AgNO3").isEmpty())
    }

    @Test
    @DisplayName("单选给多个选项：判定为违规并给出可读说明")
    fun singleWithMultipleLettersIsViolation() {
        val problem = ChoiceAnswerGuard.violation(fourOptions, "A、C", ChoiceAnswerGuard.SINGLE)
        assertNotNull(problem)
        assertTrue(problem.orEmpty().contains("单选"))
        assertTrue(problem.orEmpty().contains("2"))
    }

    @Test
    @DisplayName("多选只给一个选项：判定为违规")
    fun multiWithSingleLetterIsViolation() {
        val problem = ChoiceAnswerGuard.violation(fourOptions, "C", ChoiceAnswerGuard.MULTI)
        assertNotNull(problem)
        assertTrue(problem.orEmpty().contains("多选"))
    }

    @Test
    @DisplayName("与题型一致、题型未知、非选择题时不报警")
    fun matchingAnswersAreFine() {
        assertNull(ChoiceAnswerGuard.violation(fourOptions, "C", ChoiceAnswerGuard.SINGLE))
        assertNull(ChoiceAnswerGuard.violation(fourOptions, "B、D", ChoiceAnswerGuard.MULTI))
        assertNull(ChoiceAnswerGuard.violation(fourOptions, "A、C", ChoiceAnswerGuard.UNKNOWN))
        assertNull(ChoiceAnswerGuard.violation(emptyList(), "A、C", ChoiceAnswerGuard.SINGLE))
    }

    @Test
    @DisplayName("重答指令里写清题型与数量限制")
    fun retryInstructionStatesTheRule() {
        val single = ChoiceAnswerGuard.retryInstruction(fourOptions, "A、C", ChoiceAnswerGuard.SINGLE)
        assertNotNull(single)
        assertTrue(single.orEmpty().contains("单选题"))
        assertTrue(single.orEmpty().contains("只能包含一个选项字母"))

        val multi = ChoiceAnswerGuard.retryInstruction(fourOptions, "C", ChoiceAnswerGuard.MULTI)
        assertNotNull(multi)
        assertTrue(multi.orEmpty().contains("多选题"))
        assertTrue(multi.orEmpty().contains("至少两个"))

        // 不需要重答时返回 null，避免白白多跑一次模型
        assertNull(ChoiceAnswerGuard.retryInstruction(fourOptions, "C", ChoiceAnswerGuard.SINGLE))
    }
}
