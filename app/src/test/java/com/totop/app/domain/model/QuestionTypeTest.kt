package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 题型枚举的取值契约。
 *
 * 取值是写进提示词与客户端拼接逻辑的「线格式」，一旦重复或改名，
 * 模型给的题型会静默落到 [QuestionType.UNKNOWN]，表现为「补充要领永远不生效」。
 */
@DisplayName("题型枚举")
class QuestionTypeTest {
    @Test
    @DisplayName("线格式取值互不重复")
    fun wireValuesAreUnique() {
        val wires = QuestionType.entries.map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
    }

    @Test
    @DisplayName("能按线格式还原，未知与空值落到 UNKNOWN")
    fun fromWireResolves() {
        assertEquals(QuestionType.MATH_CHOICE, QuestionType.fromWire("math_choice"))
        assertEquals(QuestionType.ENGLISH_CONTINUATION, QuestionType.fromWire(" english_continuation "))
        assertEquals(QuestionType.UNKNOWN, QuestionType.fromWire("unknown"))
        assertEquals(QuestionType.UNKNOWN, QuestionType.fromWire("阅读理解"))
        assertEquals(QuestionType.UNKNOWN, QuestionType.fromWire(null))
        assertEquals(QuestionType.UNKNOWN, QuestionType.fromWire(""))
    }
}
