package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 答案比对与拼接的契约。
 *
 * 这里的每一条都直接决定「老师的答案写进去之后会不会被标成存疑」，
 * 判错的代价是学生要么白看到一次「存疑」，要么错过一次真实的冲突。
 */
@DisplayName("答案文本")
class AnswerTextTest {
    @Test
    @DisplayName("只吸收排版差异：空格、全角标点、句号、大小写")
    fun `排版差异视为相同`() {
        assertTrue(AnswerText.sameAs("B", " b "))
        assertTrue(AnswerText.sameAs("2X", "2x"))
        assertTrue(AnswerText.sameAs("24L", "24 L"))
        assertTrue(AnswerText.sameAs("A，C", "A,C"))
    }

    @Test
    @DisplayName("语义不同就判不同：不做数学等价")
    fun `语义不同`() {
        assertFalse(AnswerText.sameAs("1/2", "0.5"))
        assertFalse(AnswerText.sameAs("2x", "2x²"))
        assertFalse(AnswerText.sameAs("B", "B、D"))
        assertFalse(AnswerText.sameAs("24 L", "24 mL"))
    }

    @Test
    @DisplayName("拼接：空答案直接用，非空续在后面")
    fun `拼接小问答案`() {
        val parts = listOf(SolutionPart("第(2)问", "4"), SolutionPart("第(3)问", "12"))
        assertEquals("第(2)问：4；第(3)问：12", AnswerText.merge("", parts))
        assertEquals("(1) 2；第(2)问：4；第(3)问：12", AnswerText.merge("(1) 2", parts))
    }

    @Test
    @DisplayName("拼接：没有新增时原样返回")
    fun `空列表`() {
        assertEquals("(1) 2", AnswerText.merge("(1) 2", emptyList()))
    }
}
