package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 笔记分节与取舍的契约测试。
 *
 * 守的是两条产品口径：
 * 1. 复习资料里两类都要有位置，不能因为「最近都是卡点」就把重点挤没；
 * 2. 条数不再写死 8 条，改成按篇幅预算——8 条 1200 字的笔记能占掉半份资料。
 */
@DisplayName("笔记分节与取舍")
class StudyNoteDigestTest {
    private fun note(
        id: String,
        kind: StudyNoteKind,
        bodyChars: Int = 10,
        at: Long = 0,
    ) = StudyNote(
        id = id,
        kpointId = null,
        questionId = null,
        source = StudyNoteSource.COMPANION,
        kind = kind,
        title = "标题",
        body = "正".repeat(bodyChars),
        createdAtUtc = at,
        updatedAtUtc = at,
    )

    @Test
    @DisplayName("分节固定为 重点 → 卡点，空分类不出现")
    fun sectionsOrder() {
        val sections =
            StudyNoteDigest.sections(
                listOf(
                    note("s1", StudyNoteKind.STUCK),
                    note("k1", StudyNoteKind.KEY),
                ),
            )
        assertEquals(listOf(StudyNoteKind.KEY, StudyNoteKind.STUCK), sections.map { it.kind })
        assertEquals(listOf("k1"), sections[0].notes.map { it.id })

        // 只有一类时只返回一节，不要一个空标题挂在那里
        assertEquals(1, StudyNoteDigest.sections(listOf(note("k1", StudyNoteKind.KEY))).size)
        assertTrue(StudyNoteDigest.sections(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("取笔记时先重点后卡点，与传入顺序无关")
    fun pickKeepsKindOrder() {
        val picked =
            StudyNoteDigest.pick(
                listOf(
                    note("s1", StudyNoteKind.STUCK),
                    note("k1", StudyNoteKind.KEY),
                    note("k2", StudyNoteKind.KEY),
                ),
            )
        assertEquals(listOf("k1", "k2", "s1"), picked.map { it.id })
    }

    @Test
    @DisplayName("超出字数预算就停，不再看后面的条目")
    fun pickStopsAtBudget() {
        // 每条 2 + 100 = 102 字；预算 250 只装得下两条
        val notes =
            listOf(
                note("k1", StudyNoteKind.KEY, bodyChars = 100),
                note("k2", StudyNoteKind.KEY, bodyChars = 100),
                note("k3", StudyNoteKind.KEY, bodyChars = 100),
            )
        val picked = StudyNoteDigest.pick(notes, budget = 250)
        assertEquals(listOf("k1", "k2"), picked.map { it.id })
    }

    @Test
    @DisplayName("预算再小，每类也至少留一条")
    fun pickKeepsOnePerKind() {
        val notes =
            listOf(
                note("k1", StudyNoteKind.KEY, bodyChars = 100),
                note("s1", StudyNoteKind.STUCK, bodyChars = 100),
            )
        val picked = StudyNoteDigest.pick(notes, budget = 1)
        assertEquals(2, picked.size, "两类都必须有位置，否则学生会以为某一类丢了")
    }

    @Test
    @DisplayName("单类条数上限生效，防止短句刷屏")
    fun pickRespectsPerKindCap() {
        val notes = (1..15).map { note("k" + it, StudyNoteKind.KEY) }
        val picked = StudyNoteDigest.pick(notes, budget = Int.MAX_VALUE, perKind = 12)
        assertEquals(12, picked.size)
    }
}
