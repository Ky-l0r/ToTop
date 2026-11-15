package com.totop.app.domain.model

/**
 * 一个知识点上，一类题的「通用解法卡」。
 *
 * 与 [LearnedMaterial] 的分工：
 * - 资料是**某一份解析的原文**，带来源、带截图，只能按科目召回，学生存十条也只是十条；
 * - 方法卡是**同一知识点、同一方法**的多次解答归纳出来的骨架（步骤 + 适用条件 + 易错），
 *   按知识点召回，`sampleCount` 越多越可信。
 *
 * 为什么召回口径必须从「最近几条」换成这个：原文按时间倒序取三条，
 * 与本题属于哪个知识点毫无关系，等于让模型碰运气参考；而同一知识点下的题，
 * 解法骨架是稳定的——这才有「越用越准」的可能。
 *
 * 关于「归纳」的实现口径（见 docs/偏离记录.md 第 44 章）：
 * 这里**不再多调一次模型**，而是由 [KpointMethodMerge] 在客户端确定性地合并——
 * 方法名与知识点相同就累加计数、步骤取更完整的那一版。
 * 多调一次模型会同时抬高成本与延迟，而阶段 B 输出的 `method` 与 `steps` 本身
 * 已经是归纳过的结论；缺的只是「把同类题的结论叠在一起」这一步。
 */
data class KpointMethod(
    val id: String,
    val kpointId: String,
    val kpointName: String,
    val subject: String,
    /** 方法名，取自阶段 B 的 `method`（如「配方法」「动能定理」）。 */
    val name: String,
    val keySteps: List<String>,
    /** 适用条件：什么样的题该想到这个方法。 */
    val applicable: String,
    val pitfalls: List<String>,
    /** 已经被多少道同类题验证过。1 表示只出现过一次，参考价值有限。 */
    val sampleCount: Int,
    /** 最近一次用到它的题（截断的题干），让学生看得出这张卡是从哪来的。 */
    val lastTitle: String,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
) {
    /** 卡片副标题：几道题 + 适用条件。 */
    val headline: String
        get() =
            buildString {
                append(sampleCount).append(" 道同类题")
                if (applicable.isNotBlank()) append(" · ").append(applicable)
            }

    /**
     * 给阶段 B 的提示块。
     *
     * 三条硬约束与资料一致：带「仅供参考」的标注、有字数上限、只给骨架不给本题结论。
     * 多一条：显式写出「这是从同类题归纳的」，让模型知道它有统计意义但不是标准答案。
     */
    fun promptBlock(maxChars: Int = MAX_BLOCK_CHARS): String =
        buildString {
            append("【同类题的通用解法·仅供参考】")
            append(kpointName).append(" — ").append(name)
            append("（由 ").append(sampleCount).append(" 道同类题归纳）\n")
            if (applicable.isNotBlank()) append("适用：").append(applicable).append('\n')
            if (keySteps.isNotEmpty()) {
                append("通用步骤：")
                keySteps.take(MAX_STEPS).forEachIndexed { index, step ->
                    if (index > 0) append("；")
                    append(index + 1).append(") ").append(step)
                }
                append('\n')
            }
            if (pitfalls.isNotEmpty()) {
                append("常见卡点：").append(pitfalls.take(MAX_PITFALLS).joinToString("；")).append('\n')
            }
        }.take(maxChars).trim()

    companion object {
        /** 单张卡进提示词的字数上限。 */
        const val MAX_BLOCK_CHARS: Int = 400

        private const val MAX_STEPS: Int = 5

        private const val MAX_PITFALLS: Int = 2
    }
}

/**
 * 一次解答提供的「方法证据」：从这道题的标准解里抽出来的结论。
 *
 * 抽成独立类型而不是直接传 [KpointMethod]：证据是「这一道题的」，
 * 方法卡是「一类题的」，混用会让合并逻辑误以为证据已经带上了累计计数。
 */
data class MethodEvidence(
    val kpointId: String,
    val kpointName: String,
    val subject: String,
    val name: String,
    val keySteps: List<String>,
    val applicable: String,
    val pitfalls: List<String>,
    val title: String,
)

/**
 * 方法卡的合并规则（纯函数，可单测）。
 *
 * 三条：
 * 1. **同名同知识点才合并**：方法名归一化（去空白与标点、统一小写）后相等；
 *    名字不同就各存一张，列表里按 `sampleCount` 排序，学生能看出这类题最常用哪种解法；
 * 2. **步骤取更完整的那版**：新证据步骤更多就替换——更长的步骤链通常包含更多关键节点，
 *    而原来那版可能只是题目简单、步骤少，不代表归纳得更准；
 * 3. **易错合并去重**：不同题目的坑往往是同一个坑的不同说法，留前三条即可。
 */
object KpointMethodMerge {
    /** 一张卡最多记几步：再多就不是骨架而是原文了。 */
    const val MAX_STEPS: Int = 8

    /** 一张卡最多记几个易错点。 */
    const val MAX_PITFALLS: Int = 3

    /** 方法名的比较键：只保留字母数字并转小写，避免「配方法 」「配方法（二次）」被当成两种。 */
    fun nameKey(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 合并或新建。
     *
     * @param existing 同一知识点 + 同一方法名的已有卡片，没有则为 null
     * @param newId 新建时使用的 id（由调用方给 UUID，避免这里依赖随机源）
     */
    fun merge(
        existing: KpointMethod?,
        evidence: MethodEvidence,
        newId: String,
        nowUtc: Long,
    ): KpointMethod {
        val steps = evidence.keySteps.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_STEPS)
        val pitfalls = evidence.pitfalls.map { it.trim() }.filter { it.isNotBlank() }
        if (existing == null) {
            return KpointMethod(
                id = newId,
                kpointId = evidence.kpointId,
                kpointName = evidence.kpointName,
                subject = evidence.subject,
                name = evidence.name.trim(),
                keySteps = steps,
                applicable = evidence.applicable.trim(),
                pitfalls = pitfalls.distinct().take(MAX_PITFALLS),
                sampleCount = 1,
                lastTitle = evidence.title.trim(),
                createdAtUtc = nowUtc,
                updatedAtUtc = nowUtc,
            )
        }
        val mergedPitfalls =
            (existing.pitfalls + pitfalls)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(MAX_PITFALLS)
        return existing.copy(
            // 名字以最初那张为准：同一方法的另一种叫法不该把卡片改名，否则学生收藏感会断
            keySteps = if (steps.size > existing.keySteps.size) steps else existing.keySteps,
            applicable = existing.applicable.ifBlank { evidence.applicable.trim() },
            pitfalls = mergedPitfalls,
            sampleCount = existing.sampleCount + 1,
            lastTitle = evidence.title.trim().ifBlank { existing.lastTitle },
            updatedAtUtc = nowUtc,
        )
    }
}
