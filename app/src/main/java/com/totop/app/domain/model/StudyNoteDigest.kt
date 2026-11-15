package com.totop.app.domain.model

/**
 * 笔记进复习资料前的取舍（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 为什么不再「取最近 8 条」：
 * 1. 条数不反映篇幅——8 条 1200 字的笔记能占掉半份资料，8 条 80 字的又太少；
 * 2. 只按时间倒序会让某一类把另一类挤掉：最近问的都是卡点，重点就一条都进不来。
 *
 * 现在的口径：**先分类，再按字数预算取**。每类各自限量，两类都保证有位置；
 * 预算按「标题 + 正文」的字符数累计，超出即停止，但每类至少保留一条
 * （预算再紧也不能交白卷，否则学生会以为笔记丢了）。
 */
object StudyNoteDigest {
    /**
     * 复习资料里「笔记」两节合计的字数预算。
     *
     * 一份复习资料正文 6500–8000 字，笔记属于附录性质，给 3000 字左右：
     * 再多就会把正文任何一章都比下去。单条笔记上限 1200 字，因此实际条数通常 6–12 条。
     */
    const val DEFAULT_CHAR_BUDGET: Int = 3000

    /** 单类最多取几条：防止 30 条 80 字的短句把这一节变成流水账。 */
    const val MAX_NOTES_PER_KIND: Int = 12

    /** 一节笔记：某个分类下的若干条。 */
    data class Section(
        val kind: StudyNoteKind,
        val notes: List<StudyNote>,
    )

    /**
     * 按分类分节，顺序固定为 [StudyNoteKind.displayOrder]，空分类不返回。
     *
     * 组内保持传入顺序（DAO 已按时间倒序），不再重排：学生刚记的通常正是他现在要看的。
     */
    fun sections(notes: List<StudyNote>): List<Section> =
        StudyNoteKind.displayOrder.mapNotNull { kind ->
            val ofKind = notes.filter { it.kind == kind }
            if (ofKind.isEmpty()) null else Section(kind, ofKind)
        }

    /**
     * 按预算取笔记：先重点、后卡点；组内从新到旧；每类至多 [MAX_NOTES_PER_KIND] 条。
     *
     * @param budget 两节合计允许的字符数（标题 + 正文）
     */
    fun pick(
        notes: List<StudyNote>,
        budget: Int = DEFAULT_CHAR_BUDGET,
        perKind: Int = MAX_NOTES_PER_KIND,
    ): List<StudyNote> {
        val picked = ArrayList<StudyNote>(notes.size)
        var used = 0
        sections(notes).forEach { section ->
            var takenInSection = 0
            section.notes.forEach { note ->
                if (takenInSection >= perKind) return@forEach
                val cost = note.title.length + note.body.length
                val overBudget = used + cost > budget
                // 每类至少留一条：预算再紧也不能让某一类整节消失
                if (overBudget && takenInSection > 0) return@forEach
                picked += note
                used += cost
                takenInSection++
            }
        }
        return picked
    }
}
