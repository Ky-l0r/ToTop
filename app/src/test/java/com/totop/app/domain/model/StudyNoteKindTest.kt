package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 笔记分类的契约测试。
 *
 * wire 值是持久化与复习资料分节的唯一来源，改文案不能改到它。
 */
@DisplayName("笔记分类")
class StudyNoteKindTest {
    @Test
    @DisplayName("两类齐全，展示顺序为 重点 → 卡点")
    fun kindsInOrder() {
        assertEquals(listOf("key", "stuck"), StudyNoteKind.entries.map { it.wire })
        assertEquals(listOf("重点", "卡点"), StudyNoteKind.entries.map { it.label })
        assertEquals(listOf(StudyNoteKind.KEY, StudyNoteKind.STUCK), StudyNoteKind.displayOrder)
    }

    @Test
    @DisplayName("认不出的分类回退到「重点」，绝不返回空")
    fun unknownFallsBackToKey() {
        // 列表与复习资料都不接受「无分类」的笔记：回退到重点至少不会让它消失
        assertEquals(StudyNoteKind.KEY, StudyNoteKind.fromWire(null))
        assertEquals(StudyNoteKind.KEY, StudyNoteKind.fromWire(""))
        assertEquals(StudyNoteKind.KEY, StudyNoteKind.fromWire("nonsense"))
    }

    @Test
    @DisplayName("wire 解析忽略大小写与空白")
    fun parseIsLenient() {
        assertEquals(StudyNoteKind.STUCK, StudyNoteKind.fromWire(" Stuck "))
        assertEquals(StudyNoteKind.KEY, StudyNoteKind.fromWire("KEY"))
    }
}
