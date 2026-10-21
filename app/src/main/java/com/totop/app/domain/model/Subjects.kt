package com.totop.app.domain.model

/**
 * 学科代码与中文名的映射。
 *
 * 数据层用英文代码（math / chinese / english …），界面一律显示中文，
 * 避免把内部字段直接暴露给学生。
 */
object Subjects {
    /** 界面上的筛选顺序；「全部」由 UI 自行前置。 */
    val ORDER: List<String> = listOf("chinese", "math", "english", "physics", "chemistry", "biology", "history", "geography", "politics")

    private val LABELS: Map<String, String> =
        mapOf(
            "chinese" to "语文",
            "math" to "数学",
            "english" to "英语",
            "physics" to "物理",
            "chemistry" to "化学",
            "biology" to "生物",
            "history" to "历史",
            "geography" to "地理",
            "politics" to "政治",
        )

    /** 代码转中文；未知代码原样返回，便于发现新的学科值。 */
    fun label(code: String): String = LABELS[code.trim().lowercase()] ?: code

    /** 中文转代码；识别不了时回退 math。 */
    fun codeOf(label: String): String =
        LABELS.entries.firstOrNull { it.value == label }?.key ?: "math"

    /** 是否有中文名。 */
    fun isKnown(code: String): Boolean = LABELS.containsKey(code.trim().lowercase())
}
