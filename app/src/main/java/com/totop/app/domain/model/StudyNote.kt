package com.totop.app.domain.model

/**
 * 学习笔记的来源。
 *
 * 只保留三种必需来源，不做开放枚举：来源决定「这条笔记是谁写的」，
 * 而复习资料里要据此说明「这些是你自己问过/核过的点，不是系统推的」。
 */
enum class StudyNoteSource(
    val wire: String,
    val label: String,
) {
    /** 陪伴作业里问出来的一段回答。 */
    COMPANION("companion", "陪伴问答"),

    /** 自己核对答案时留下的结论。 */
    CHECK("check", "核对"),

    /** 手动写下的。 */
    MANUAL("manual", "手记"),
    ;

    companion object {
        fun fromWire(raw: String): StudyNoteSource = entries.firstOrNull { it.wire == raw } ?: MANUAL
    }
}

/**
 * 一条学习笔记（`study_note` 表）。
 *
 * 设计约束（见 docs/偏离记录.md 第 40 节）：
 * - [kpointId] **只能挂已有节点**，不新增节点。知识点树是掌握度、推荐路径与阶段 D 候选的
 *   唯一锚点，自造节点会让这三件事同时失效，所以宁可这条笔记「没有归属」；
 * - [body] 有长度上限：笔记要进复习资料，无上限的长文会把整章的排版挤垮；
 * - [questionId] 可空：陪伴页的问答不属于某道题，只有核对场景才带题。
 */
data class StudyNote(
    val id: String,
    val kpointId: String?,
    val questionId: String?,
    val source: StudyNoteSource,
    val title: String,
    val body: String,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
) {
    val hasKpoint: Boolean get() = !kpointId.isNullOrBlank()

    companion object {
        /** 正文上限：约等于复习资料里一个小节的容量，再多就该自己写总结了。 */
        const val MAX_BODY_CHARS: Int = 1200

        /** 标题上限：卡片与 PDF 都只放得下一行。 */
        const val MAX_TITLE_CHARS: Int = 40

        /** 从正文取第一句非空内容当标题；取不到时用来源作标题。 */
        fun titleOf(
            body: String,
            source: StudyNoteSource,
        ): String {
            val firstLine =
                body
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            if (firstLine.isEmpty()) return source.label
            val cleaned = firstLine.removePrefix("#").trim()
            return if (cleaned.length <= MAX_TITLE_CHARS) cleaned else cleaned.take(MAX_TITLE_CHARS - 1) + "…"
        }

        /** 正文截断：超出上限时保留前半段并标明截断，不静默丢弃。 */
        fun clampBody(body: String): String {
            val trimmed = body.trim()
            if (trimmed.length <= MAX_BODY_CHARS) return trimmed
            return trimmed.take(MAX_BODY_CHARS - 12).trimEnd() + "…（已截断）"
        }
    }
}
