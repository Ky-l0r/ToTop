package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 易错题状态的契约测试。
 *
 * 关键一条：wire 值是持久化与筛选参数的唯一来源，改文案不能改到它，
 * 否则升级后老数据的筛选会全部落空。
 */
@DisplayName("易错题状态")
class MistakeStatusTest {
    @Test
    @DisplayName("四态齐全，顺序即界面顺序")
    fun statesInOrder() {
        // 2026-11-12 撤销「待校对」：它只是「卷面上有手写」的副作用，不代表任何可执行的处置。
        // 现在顺序是 不会 → 错误 → 存疑 → 理解：先看会不会，再看错没错，再谈结论定没定、懂没懂。
        assertEquals(
            listOf("unknown", "wrong", "disputed", "understood"),
            MistakeStatus.entries.map { it.wire },
        )
        assertEquals(
            listOf("不会", "错误", "存疑", "理解"),
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
    @DisplayName("已撤销的 pending_check 存量数据读成「错误」，不丢记录")
    fun legacyPendingCheckReadsAsWrong() {
        // 这条守的是「删枚举值但不做数据库迁移」的安全性：
        // 老库、旧备份、以及从别人手机导回的数据包里都还可能有 pending_check。
        // 一旦有人把回退策略从「当错误」改成「当无效丢弃」，这批记录会从列表里凭空消失。
        assertEquals(MistakeStatus.WRONG, MistakeStatus.fromWireOrWrong(MistakeStatus.LEGACY_PENDING_CHECK))
        assertEquals(MistakeStatus.WRONG, MistakeStatus.fromWireOrWrong(" PENDING_CHECK "))
        // 枚举里不该再有它，否则筛选条会多出一个永远空的分类
        assertNull(MistakeStatus.ofWire(MistakeStatus.LEGACY_PENDING_CHECK))
        assertTrue(MistakeStatus.entries.none { it.wire == MistakeStatus.LEGACY_PENDING_CHECK })
    }

    @Test
    @DisplayName("wire 解析忽略大小写与空白")
    fun parseIsLenient() {
        assertEquals(MistakeStatus.DISPUTED, MistakeStatus.ofWire(" Disputed "))
        assertNull(MistakeStatus.ofWire("solved"))
    }

    @Test
    @DisplayName("自动判定只有两条分支：有错因→错误，其余→不会")
    fun decideByFirstErrorOnly() {
        assertEquals(MistakeStatus.WRONG, MistakeStatus.decide(hasFirstError = true))
        // 没定位到错因的三种情形（没写、明说不会、写了过程但指不出错）共用同一个结果：
        // 既然学生还要问，就说明没掌握。
        assertEquals(MistakeStatus.UNKNOWN, MistakeStatus.decide(hasFirstError = false))
        // 存疑与理解不由自动判定产生，判据里不该出现它们
        assertTrue(MistakeStatus.decide(false) != MistakeStatus.DISPUTED)
        assertTrue(MistakeStatus.decide(false) != MistakeStatus.UNDERSTOOD)
    }
}
