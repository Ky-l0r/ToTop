package com.totop.app.domain.model

/**
 * 模型重写后的一张「方法骨架」。
 *
 * 只有这三样能被模型改写：步骤、适用条件、易错点。
 * 方法名、知识点归属、被验证次数都不在模型手里——
 * 它一旦能改名，同一张卡就会在「配方法 / 配方法求顶点」之间反复横跳，学生的收藏感会断。
 */
data class MethodSkeleton(
    val steps: List<String>,
    val applicable: String = "",
    val pitfalls: List<String> = emptyList(),
)

/**
 * 「样本够了就整卡重归纳」的触发条件与落地约束（纯逻辑，可单测）。
 *
 * 为什么要重归纳：合并规则是**确定性**的——同名同知识点就累加计数、步骤取更长的那版
 * （见 [KpointMethodMerge]）。这解决了「把同类题叠起来」，但叠出来的步骤仍然带着各题的
 * 具体数字与字母；做到第三、第四道同类题时，它已经不像骨架，更像几道题的拼贴。
 *
 * 为什么只在第 3 道题之后重写一次：
 * - 第 1、2 道题时样本太少，重写只会把一道题的特殊做法固化成「通用路径」；
 * - 也不能每次都写——那等于每做一题多付一次调用，正是仓储层刻意避免的成本；
 * - 用计数本身当开关，不必新增「重写过没有」的列：`sampleCount` 每次只加 1，
 *   它等于 3 的那一次有且只有一次。代价是**失败不重试**（见下）。
 *
 * 已知代价：[apply] 拿不到可用步骤时整张卡保持原样；若那次调用失败或被取消，
 * 这张卡就停在「合并态」，不会再重写。这是有意的取舍——重写是锦上添花，
 * 而为了它加一列迁移、或每做一题重试一次，都不划算。
 */
object KpointMethodRewrite {
    /** 攒够几道同类题才值得整卡重写一次。 */
    const val SAMPLE_THRESHOLD: Int = 3

    /** 单步字数上限：超过就是一句话讲了一整道题，不是骨架。 */
    const val MAX_STEP_CHARS: Int = 60

    /** 适用条件上限：一句话说清「什么样的题该想到它」。 */
    const val MAX_APPLICABLE_CHARS: Int = 40

    /** 单条易错点上限。 */
    const val MAX_PITFALL_CHARS: Int = 40

    /** 是否该重写：只在计数正好等于阈值时触发一次。 */
    fun shouldRewrite(sampleCount: Int): Boolean = sampleCount == SAMPLE_THRESHOLD

    /**
     * 把模型给的骨架贴回卡片。
     *
     * 三条保守规则，都是「宁可保留旧的，也不写进半截」：
     * 1. 步骤清洗后为空 -> 整张卡原样返回（模型这次没说人话，不该把卡清空）；
     * 2. 易错点为空 -> 保留原卡片的易错点（模型没提，不代表那些坑不存在）；
     * 3. 适用条件为空 -> 保留原值（原值来自「题干求什么」，本身有事实依据）。
     */
    fun apply(
        card: KpointMethod,
        draft: MethodSkeleton,
        nowUtc: Long,
    ): KpointMethod {
        val steps = clean(draft.steps, KpointMethodMerge.MAX_STEPS, MAX_STEP_CHARS)
        if (steps.isEmpty()) return card
        val pitfalls = clean(draft.pitfalls, KpointMethodMerge.MAX_PITFALLS, MAX_PITFALL_CHARS)
        return card.copy(
            keySteps = steps,
            applicable = draft.applicable.trim().take(MAX_APPLICABLE_CHARS).ifBlank { card.applicable },
            pitfalls = pitfalls.ifEmpty { card.pitfalls },
            updatedAtUtc = nowUtc,
        )
    }

    /** 去空白、去重、截断、限条数：四件事一起做，避免模型输出把卡片撑变形。 */
    private fun clean(
        values: List<String>,
        limit: Int,
        maxChars: Int,
    ): List<String> =
        values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.take(maxChars) }
            .distinct()
            .take(limit)
}
