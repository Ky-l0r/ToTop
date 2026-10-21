package com.totop.app.domain.model

/**
 * 学科代码与中文名的映射。
 *
 * 数据层用英文代码（math / chinese / english …），界面一律显示中文，
 * 避免把内部字段直接暴露给学生。
 *
 * 覆盖范围：国家课程标准的九门文化课 + 浙江省选考的「技术」
 * （技术 = 信息技术 + 通用技术，浙江单独设科，因此单列一个代码）。
 * 另设 [GENERAL] 作为「不属于以上任何一科」的兜底，避免模型返回奇怪科目时
 * 数据被强行归到数学名下（旧实现就是一律回退 math，导致错题本统计串科）。
 */
object Subjects {
    /**
     * 界面上的筛选顺序：语文、数学、英语、物理、化学、生物、政治、历史、地理、技术。
     * 「全部」由 UI 自行前置。
     */
    val ORDER: List<String> =
        listOf(
            "chinese",
            "math",
            "english",
            "physics",
            "chemistry",
            "biology",
            "politics",
            "history",
            "geography",
            "technology",
        )

    /** 兜底科目代码：模型给了识别不了的科目时用它，而不是硬塞进数学。 */
    const val GENERAL: String = "general"

    /** 科目代码 -> 中文名。顺序即展示顺序，便于直接转列表。 */
    private val LABELS: Map<String, String> =
        linkedMapOf(
            "chinese" to "语文",
            "math" to "数学",
            "english" to "英语",
            "physics" to "物理",
            "chemistry" to "化学",
            "biology" to "生物",
            "politics" to "政治",
            "history" to "历史",
            "geography" to "地理",
            "technology" to "技术",
            GENERAL to "通用",
        )

    /** 界面全部可选项的中文名（含兜底科目）。 */
    val ALL_LABELS: List<String> = LABELS.values.toList()

    /** 代码转中文；未知代码原样返回，便于发现新的学科值。 */
    fun label(code: String): String = LABELS[code.trim().lowercase()] ?: code

    /**
     * 中文转代码。
     *
     * 识别不了时返回 [GENERAL] 而不是 math：
     * 旧实现回退 math 会让「通用」「综合」这类题的错题悄悄计入数学，
     * 学生在数学错题本里看到一道物理题，无法理解也无法纠正。
     */
    fun codeOf(label: String): String {
        val token = label.trim()
        LABELS.entries.firstOrNull { it.value == token }?.let { return it.key }
        // 允许直接用代码当标签（设置页与提示词里两种写法都会出现）
        val lower = token.lowercase()
        return if (LABELS.containsKey(lower)) lower else GENERAL
    }

    /** 是否有中文名。 */
    fun isKnown(code: String): Boolean = LABELS.containsKey(code.trim().lowercase())

    /**
     * 归一化模型返回的科目字段。
     *
     * 模型可能返回 "数学"、"math"、"Math"、"初中数学"、"高中数学" 等形态，
     * 这里按「包含关系」做最后一道兜底，命中不了就落到 [GENERAL]。
     */
    fun normalize(raw: String?): String {
        val token = raw?.trim().orEmpty()
        if (token.isEmpty()) return GENERAL
        val lower = token.lowercase()
        LABELS.entries.firstOrNull { it.key == lower }?.let { return it.key }
        LABELS.entries.firstOrNull { it.value == token }?.let { return it.key }
        // 「初中数学」「高中物理」这类带学段前缀的写法
        LABELS.entries.firstOrNull { it.value in token }?.let { return it.key }
        LABELS.entries.firstOrNull { lower.contains(it.key) }?.let { return it.key }
        // 技术的常见别名
        if (token.contains("信息技术") || token.contains("通用技术") || token.contains("技术")) return "technology"
        return GENERAL
    }
}
