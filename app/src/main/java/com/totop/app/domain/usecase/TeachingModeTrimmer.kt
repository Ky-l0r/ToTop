package com.totop.app.domain.usecase

import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.SummaryCard
import com.totop.app.domain.model.TeachingMode

/**
 * 一级提示（开发方案 FR-2：GUIDED「不返回 finalAnswer，返回三级提示」）。
 *
 * 三级递进，**任何一级都不含最终答案**：
 * 1 方向：只需要知道该往哪儿想（方法名）；
 * 2 关键：需要知道最容易卡住的那一步「为什么」；
 * 3 下一步：需要知道眼下具体该做什么，但仍然要自己算出结果。
 *
 * @param level 1..3
 * @param title 短标题（提示卡头部）
 * @param hint 提示正文
 * @param source 该提示的出处（便于排查「提示是从哪一步派生的」）
 */
data class HintLevel(
    val level: Int,
    val title: String,
    val hint: String,
    val source: String = "",
) {
    init {
        require(level in 1..3) { "提示级别必须为 1..3，实际为：" + level }
    }
}

/**
 * 按讲解模式下发的解题结果（同一份落库数据，三种视角）。
 *
 * 这是「模式裁剪」的唯一出口：UI 只拿到这个对象，
 * 因此 GUIDED 不可能从其它字段里摸到答案。
 *
 * @param solutionSteps DIRECT 为完整步骤；GUIDED 为空，需学生主动揭示
 * @param finalAnswer 仅 DIRECT 有值；GUIDED 为 null
 * @param method 方法名：两档都有值（属于「方向」，不算答案）
 * @param hints 三级提示，仅 GUIDED 有值
 * @param guidance 引导提问，仅 GUIDED 有值（不给算式、不给答案）
 */
data class SolvedView(
    val mode: TeachingMode,
    val question: Question,
    val solutionSteps: List<SolutionStep>,
    val finalAnswer: String?,
    val method: String?,
    val firstError: FirstError?,
    val kpoints: List<KpointAssignment>,
    val summaryCard: SummaryCard?,
    val hints: List<HintLevel>,
    val guidance: List<String>,
    val aiGenerated: Boolean,
    val answerWithheld: Boolean,
) {
    /** 是否包含完整答案（用于 UI 展示「点开看全解」入口）。 */
    val hasFullSolution: Boolean get() = solutionSteps.isNotEmpty() && finalAnswer != null

    /** 未提供学生过程且未定位到错因时为 true，UI 需展示「补充你的解题过程可定位错因」。 */
    val errorUnlocated: Boolean get() = firstError == null
}

/**
 * 讲解模式裁剪器（开发方案 FR-2 三档模式表格）。
 *
 * 纯函数、零成本、零 IO：**不额外调用模型**，只对同一份落库结果做视角裁剪。
 * 这一点很关键——若为了 GUIDED 再调一次模型，用户的 token 成本会翻倍。
 *
 * | 模式 | 标准步骤 | finalAnswer | 三级提示 | 引导提问 |
 * | --- | --- | --- | --- | --- |
 * | DIRECT | 全量 | 有 | 无 | 无 |
 * | GUIDED | 无（需学生点「看全解」） | 无 | 三级 | 有 |
 *
 * 注意：裁剪只影响**下发**，落库数据始终是完整的（captain 明确要求「标准答案照常落库」），
 * 因此用户点「我已想通，看全解」时可以从库里取全解，不必再花钱。
 */
object TeachingModeTrimmer {
    /**
     * 裁剪。
     *
     * @param mode 当前讲解模式
     * @param question 题目
     * @param solution 标准解答（来自落库数据，始终完整）
     * @param firstError 首个错误（可能为 null：学生未提供过程）
     * @param kpoints 知识点归属
     * @param summaryCard 摘要卡
     * @param aiGenerated 是否为 AI 生成内容（展示层必须标注）
     */
    fun trim(
        mode: TeachingMode,
        question: Question,
        solution: StandardSolution,
        firstError: FirstError?,
        kpoints: List<KpointAssignment>,
        summaryCard: SummaryCard?,
        aiGenerated: Boolean,
    ): SolvedView =
        when (mode) {
            TeachingMode.DIRECT ->
                SolvedView(
                    mode = mode,
                    question = question,
                    solutionSteps = solution.steps,
                    finalAnswer = solution.finalAnswer.takeIf { it.isNotBlank() },
                    method = solution.method.takeIf { it.isNotBlank() },
                    firstError = firstError,
                    kpoints = kpoints,
                    summaryCard = summaryCard,
                    hints = emptyList(),
                    guidance = emptyList(),
                    aiGenerated = aiGenerated,
                    answerWithheld = false,
                )

            // 引导模式：步骤与答案都不下发，只给方向（method）、引导性问题与三级提示。
            //
            // 0.2.0 的变化：旧实现在这里下发完整 solutionSteps，
            // 结果「提示引导」和「直接讲解」看起来一模一样，学生一进来就看到答案。
            // 现在步骤必须由学生点「我已想通，看全解」才揭示（见 GuidePolicy.withholdsSolution）。
            TeachingMode.GUIDED ->
                SolvedView(
                    mode = mode,
                    question = question,
                    solutionSteps = emptyList(),
                    finalAnswer = null,
                    method = solution.method.takeIf { it.isNotBlank() },
                    firstError = firstError,
                    kpoints = kpoints,
                    summaryCard = summaryCard,
                    hints = buildHints(solution, firstError),
                    guidance = buildGuidance(question, solution, firstError),
                    aiGenerated = aiGenerated,
                    answerWithheld = true,
                )
        }

    /**
     * 由标准解答派生三级提示。
     *
     * 派生规则（保证不泄漏答案）：
     * - 1 级：方法名 + 第一步的「为什么」（只讲方向，不讲结果）；
     * - 2 级：出现错误步骤时用该步的「为什么」，否则用中间步骤的「为什么」；
     * - 3 级：出错步骤（或第一步）的「做什么」，但把形如等号右侧的结论裁掉。
     *
     * 任何一级都不输出 [StandardSolution.finalAnswer]。
     */
    fun buildHints(
        solution: StandardSolution,
        firstError: FirstError?,
    ): List<HintLevel> {
        val steps = solution.steps
        val method = solution.method.ifBlank { "先判断题型，再选通用思路" }
        val hints = mutableListOf<HintLevel>()

        hints +=
            HintLevel(
                level = 1,
                title = "第一级提示：方向",
                hint = "本题考查「" + method + "」。先别急着算，想清楚题目给了什么条件、要你求什么。",
                source = "method",
            )

        val anchorIdx =
            firstError?.stepIdx?.let { errIdx ->
                steps.firstOrNull { it.idx == errIdx } ?: steps.getOrNull(errIdx - 1)
            } ?: steps.getOrNull((steps.size - 1) / 2)
        val anchorWhy = anchorIdx?.why?.takeIf { it.isNotBlank() }
        hints +=
            HintLevel(
                level = 2,
                title = "第二级提示：关键",
                hint = anchorWhy ?: "先写出已知与所求的对应关系，再考虑用哪个公式或定理。",
                source = if (anchorIdx != null) "step " + anchorIdx.idx + ".why" else "fallback",
            )

        val thirdFrom =
            firstError?.stepIdx?.let { errIdx ->
                steps.firstOrNull { it.idx == errIdx } ?: steps.getOrNull(errIdx - 1)
            } ?: steps.firstOrNull()
        hints +=
            HintLevel(
                level = 3,
                title = "第三级提示：下一步",
                hint =
                    if (thirdFrom != null && thirdFrom.content.isNotBlank()) {
                        withholdConclusion(thirdFrom.content)
                    } else {
                        "把上一步的结论代入题目条件，看看能不能把未知量一步一步消掉。"
                    },
                source = if (thirdFrom != null) "step " + thirdFrom.idx + ".content" else "fallback",
            )
        return hints
    }

    /**
     * 由题目与错因派生引导提问（只给提问与方向，不给答案与算式）。
     *
     * 提问模板取自「本题应有的思考入口」，并追问「为什么原来的思路走不通」，
     * 对应 FR-1.6 要求的对比说明，但改写成提问式而不给结论。
     *
     * 0.2.0 的措辞调整（对应真机反馈「表述怪怪的」）：
     * 原来的写法混用了第三人称（「学生思路」「已知条件有 3 条」）和生硬的模板腔，
     * 学生读起来像在看成人的分析报告。现在统一成**第二人称、对着学生说话**，
     * 每条都落到一个能立刻回答的具体动作上。
     */
    fun buildGuidance(
        question: Question,
        solution: StandardSolution,
        firstError: FirstError?,
    ): List<String> {
        val ask = question.ask.trim()
        val guidance = mutableListOf<String>()
        guidance +=
            if (ask.isNotEmpty()) {
                "题目要你求的是「" + ask + "」。先用一句话说说：这属于哪一类问题？"
            } else {
                "先用你自己的话说一遍：这道题到底在问什么？"
            }
        if (question.known.isNotEmpty()) {
            guidance += "题目给的条件里，你觉得哪一条最可能决定解题方向？挑一条说说理由。"
        }
        solution.method.takeIf { it.isNotBlank() }?.let { method ->
            guidance += "如果从「" + method + "」入手，你打算第一步先写什么？"
        }
        firstError?.correctThinking?.takeIf { it.isNotBlank() }?.let { thinking ->
            guidance += "给你一个思考入口：" + withholdConclusion(thinking) + "。对照一下，你原来的切入点差在哪里？"
        }
        if (guidance.size < MIN_GUIDANCE_QUESTIONS) {
            guidance += "把你现在的思路写下来，我们一步步看它卡在哪。"
        }
        return guidance
    }

    /**
     * 去掉「结论」部分：把 `=` / `所以` / `因此` / `即` 之后的结论裁掉，
     * 只保留动作描述，避免提示变成答案。
     *
     * 例：`求导得 f'(x)=3x^2-3，令其为零解得 x=±1` -> `求导得 f'(x)=3x^2-3`
     */
    fun withholdConclusion(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val cutMarkers = listOf("，所以", "，因此", "，即", "。所以", "。因此", ";所以", "，解得", "，得解")
        var result = trimmed
        for (marker in cutMarkers) {
            val idx = result.indexOf(marker)
            if (idx > 0) {
                result = result.substring(0, idx)
                break
            }
        }
        return result.trimEnd('，', '。', ',', '.', '；', ';')
    }

    /** 引导模式至少给出这么多条引导，避免「只返回一句空话」。 */
    const val MIN_GUIDANCE_QUESTIONS: Int = 2
}
