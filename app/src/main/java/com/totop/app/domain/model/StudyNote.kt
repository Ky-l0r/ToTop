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
 * 笔记的分类。
 *
 * 为什么只分两类：学生记笔记的动机只有两种——「这个结论必须记住」和「这个点我还没想通」。
 * 再加「方法」「例题」这类分法，学生每次存都要先做一次归类判断，最后只会都选第一个。
 *
 * wire 值参与持久化与复习资料的分节，改文案不影响历史数据。
 */
enum class StudyNoteKind(
    val wire: String,
    val label: String,
    /** 一句话解释，用在板块的节标题下。 */
    val hint: String,
) {
    /** 重点：结论、公式、必须记住的一句话。 */
    KEY("key", "重点", "必须记住的结论与公式"),

    /** 卡点：还没想通、需要再问一遍的地方。 */
    STUCK("stuck", "卡点", "还没想通的点，下次先看这里"),

    ;

    companion object {
        /**
         * 默认分类：重点。
         *
         * 历史数据（v8 之前）全部是「陪伴问答里讲清楚的那段话」，本质是结论，
         * 迁移时按重点回填；新存的卡点由学生自己选。
         */
        val DEFAULT: StudyNoteKind = KEY

        /** 展示顺序：先重点、后卡点——复习时先看结论，再看自己卡住的地方。 */
        val displayOrder: List<StudyNoteKind> = listOf(KEY, STUCK)

        /** 按 wire 解析；认不出时回退到 [DEFAULT]，绝不返回 null（列表不接受无分类的笔记）。 */
        fun fromWire(raw: String?): StudyNoteKind =
            entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: DEFAULT
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
    /** 重点 / 卡点；老数据由迁移回填为 [StudyNoteKind.KEY]。 */
    val kind: StudyNoteKind,
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
