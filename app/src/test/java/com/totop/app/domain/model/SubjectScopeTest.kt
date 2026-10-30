package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 科目口径的契约测试。
 *
 * 这些用例对应真机上最容易出错的两件事：
 * 1. 存储值坏掉（空、含非法代码）时，不能把整页列表变成空白；
 * 2. `general`（未知）不能被当成「技术」——它不是一门课。
 */
@DisplayName("学生科目口径")
class SubjectScopeTest {
    @Test
    @DisplayName("没存过（空值）时按全选处理")
    fun blankMeansAll() {
        assertEquals(SubjectScope.ALL, SubjectScope.parse(null))
        assertEquals(SubjectScope.ALL, SubjectScope.parse(""))
        assertEquals(SubjectScope.ALL, SubjectScope.parse("   "))
    }

    @Test
    @DisplayName("正常读写往返，顺序按科目表固定")
    fun roundTripKeepsOrder() {
        val stored = SubjectScope.format(setOf("chemistry", "math"))
        assertEquals("math,chemistry", stored)
        assertEquals(setOf("math", "chemistry"), SubjectScope.parse(stored))
    }

    @Test
    @DisplayName("非法代码被丢弃；全非法时回落到全选，不返回空集合")
    fun illegalCodesFallBack() {
        assertEquals(setOf("chemistry"), SubjectScope.parse("chemistry,foo"))
        // 空集合会让所有列表空白，而用户无法分辨是自己选错了还是数据丢了
        assertEquals(SubjectScope.ALL, SubjectScope.parse("foo,bar"))
    }

    @Test
    @DisplayName("general 不是可选科目：写进存储值时被丢弃")
    fun generalIsNotPickable() {
        assertFalse(SubjectScope.PICKABLE.contains(Subjects.GENERAL))
        assertEquals(SubjectScope.ALL, SubjectScope.parse("general"))
    }

    @Test
    @DisplayName("未知科目的题在「全部」里可见，但不属于任何已选科目")
    fun generalStaysVisible() {
        val allowed = setOf("chemistry")
        assertTrue(SubjectScope.isVisible(Subjects.GENERAL, allowed))
        assertTrue(SubjectScope.isVisible("chemistry", allowed))
        assertFalse(SubjectScope.isVisible("math", allowed))
    }

    @Test
    @DisplayName("未知科目标注为「未知」，不叫「通用」")
    fun generalLabelIsUnknown() {
        assertEquals("未知", Subjects.label(Subjects.GENERAL))
        // 技术是真实科目，通用技术属于它；未知不能被映射过去
        assertEquals("技术", Subjects.label("technology"))
    }
}
