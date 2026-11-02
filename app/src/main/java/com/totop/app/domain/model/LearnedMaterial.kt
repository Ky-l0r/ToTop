package com.totop.app.domain.model

/**
 * 一条「资料」：学生从别处收集来的解析，供 AI 在解题时参考。
 *
 * 与笔记（study_note）的区别：笔记是**学生自己的话**，资料是**别人的讲法**。
 * 分开存是因为两者的信任级别不同——资料可能来自作业帮这类第三方，可能有错，
 * 因此它进提示词时必须带来源标注，并且只能作参考，不能覆盖模型自己的推导。
 */
data class LearnedMaterial(
    val id: String,
    val subject: String,
    val kpointId: String?,
    val kpointName: String,
    val title: String,
    val method: String,
    val keySteps: List<String>,
    val answer: String,
    val pitfalls: List<String>,
    val sourceLabel: String,
    val imagePath: String?,
    val createdAtUtc: Long,
) {
    /** 列表里的一行摘要。 */
    val summary: String
        get() =
            listOf(method, answer)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()

    /**
     * 给阶段 B 的参考块。
     *
     * 三条硬约束：
     * 1. 带来源——模型要知道这不是它自己的结论；
     * 2. 有长度上限——资料一多会把题干与候选知识点挤出预算；
     * 3. 只给方法、步骤要点与结论，不给整段解析原文（原文通常是长篇口语，性价比低）。
     */
    fun promptBlock(maxChars: Int = MAX_BLOCK_CHARS): String =
        buildString {
            append("【学生收集的资料·仅供参考】")
            append(title.ifBlank { kpointName })
            if (sourceLabel.isNotBlank()) append("（来源：").append(sourceLabel).append("）")
            append('\n')
            if (method.isNotBlank()) append("方法：").append(method).append('\n')
            if (keySteps.isNotEmpty()) {
                append("步骤：")
                keySteps.take(MAX_STEPS).forEachIndexed { index, step ->
                    if (index > 0) append("；")
                    append(index + 1).append(") ").append(step)
                }
                append('\n')
            }
            if (answer.isNotBlank()) append("结论：").append(answer).append('\n')
            if (pitfalls.isNotEmpty()) {
                append("易错：").append(pitfalls.take(MAX_PITFALLS).joinToString("；")).append('\n')
            }
        }.take(maxChars).trim()

    companion object {
        /** 单条资料进提示词的字数上限。 */
        const val MAX_BLOCK_CHARS: Int = 400

        /** 最多列出几步：再多就不是「要点」而是「原文」了。 */
        private const val MAX_STEPS: Int = 5

        private const val MAX_PITFALLS: Int = 2
    }
}
