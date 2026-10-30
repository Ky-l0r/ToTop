package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 易错题四态的契约测试。
 *
 * 关键一条：wire 值是持久化与筛选参数的唯一来源，改文案不能改到它，
 * 否则升级后老数据的筛选会全部落空。
 */
@DisplayName("易错题状态")
class MistakeStatusTest {
    @Test
    @DisplayName("四态齐全，顺序即界面顺序")
    fun fourStatesInOrder() {
        assertEquals(
            listOf("unknown", "pending_check", "wrong", "understood"),
            MistakeStatus.entries.map { it.wire },
        )
        assertEquals(
            listOf("不会", "待校对", "错误", "理解"),
            MistakeStatus.entries.map { it.label },
        )
    }

    @Test
    @DisplayName("历史数据缺省为「错误」")
    fun defaultIsWrong() {
        assertEquals(MistakeStatus.WRONG, MistakeStatus.DEFAULT)
        assertEquals(MistakeStatus.WRONG, MistakeStatus.fromWireOrWrong(null))
        assertEquals(MistakeStatus.WRONG, MistakeStatus.fromWireOrWrong(""))
        assertEquals(MistakeStatus.WRONG, MistakeStatus.fromWireOrWrong("nonsense"))
    }

    @Test
    @DisplayName("wire 解析忽略大小写与空白")
    fun parseIsLenient() {
        assertEquals(MistakeStatus.PENDING_CHECK, MistakeStatus.ofWire(" Pending_Check "))
        assertNull(MistakeStatus.ofWire("solved"))
    }
}
