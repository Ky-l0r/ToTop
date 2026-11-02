package com.totop.app.domain.model

/** 标准解答的单步：做什么 + 为什么 + 结论，对应开发方案 FR-1.3。 */
data class SolutionStep(
    /** 步骤序号，从 1 开始连续递增。 */
    val idx: Int,
    /** 本步做什么（含结论）。 */
    val content: String,
    /** 为什么这样做。 */
    val why: String,
)

/** 标准解答（阶段 B 独立求解结果，开发方案 6.4 standardSolution）。 */
data class StandardSolution(
    /** 编号步骤链。 */
    val steps: List<SolutionStep> = emptyList(),
    /** 最终答案。 */
    val finalAnswer: String = "",
    /** 使用的方法，例如「导数法」。 */
    val method: String = "",
) {
    val stepCount: Int get() = steps.size

    fun stepAt(idx: Int): SolutionStep? = steps.firstOrNull { it.idx == idx }

    /** 步骤序号是否从 1 开始连续递增（严格校验用）。 */
    fun hasContiguousStepIndex(): Boolean =
        steps.isNotEmpty() && steps.mapIndexed { index, step -> step.idx == index + 1 }.all { it }
}

/** 标准解答步骤的短别名，便于写作 `List<Step>`。 */
typealias Step = SolutionStep

/**
 * 追问补出来的某一个小问的答案。
 *
 * 为什么需要它：多小问的大题第一次可能只讲清第 (1) 问，第 (2)(3) 问是学生追问才得到的。
 * 那份答案原本只存在聊天记录里，错题本永远是残缺的；带着 [label] 一起落库，
 * 复习时才看得出「这一条是后来补的」。
 */
data class SolutionPart(
    /** 小问标记，例如「第(2)问」；模型没写小问号时可能是「(2)」。 */
    val label: String,
    /** 该小问最终给出的答案。 */
    val answer: String,
)
