package com.totop.app.data.remote

import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.ChoiceAnswerGuard
import kotlinx.coroutines.CancellationException

/** 题型重答的结果：采用哪一份原始输出、对应解析结果，以及额外消耗的 token。 */
internal data class ChoiceEnforcement(
    /** 重答成功且符合题型时才非空；为空表示沿用第一次的结果。 */
    val raw: String? = null,
    val solution: AiStandardSolution? = null,
    val usage: TokenUsage = TokenUsage.EMPTY,
)

/**
 * 答案的选项个数与题型不符时，定向重答一次（最多一次）。
 *
 * 为什么值得多花一次调用：单选答成「A、C」、多选只给「C」，
 * 错题本里存下来的就是一个错答案，学生复习时照着错的记，比不答更糟。
 *
 * 边界：重答只追加一条「本题是单选题/多选题，答案只能有几个」的硬约束，
 * 不改题目、不注入学生过程，阶段 B 的隔离性不受影响。
 * 仍然对不上时不改数据，只把差异写进 [warnings]，由界面明确告知。
 *
 * 单独放在一个文件里（而不是留在 [AiGateway] 内）：网关本身已经贴着类长阈值，
 * 这段逻辑独立、只依赖「再调一次阶段 B」，抽出来两边都更好读。
 *
 * @param callStageB 再发起一次阶段 B 的方式，由调用方注入（它握有凭据、模型与事件通道）
 */
internal suspend fun enforceChoiceAnswer(
    question: AiQuestion,
    prompt: AssembledPrompt,
    firstSolution: AiStandardSolution,
    warnings: MutableList<String>,
    callStageB: suspend (AssembledPrompt) -> StageCallResult,
): ChoiceEnforcement {
    val choiceType = ChoiceAnswerGuard.normalize(question.choiceType)
    val instruction =
        ChoiceAnswerGuard.retryInstruction(
            options = question.options,
            finalAnswer = firstSolution.finalAnswer.orEmpty(),
            choiceType = choiceType,
        ) ?: return ChoiceEnforcement()
    val retried =
        try {
            callStageB(prompt.copy(userPrompt = prompt.userPrompt + "\n\n" + instruction))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LlmException) {
            warnings.add("按题型重答失败，已保留原答案：" + failure.error.userMessage)
            return ChoiceEnforcement()
        }
    val candidate = AiGateway.decodeStandardSolution(retried.raw)
    val problem =
        ChoiceAnswerGuard.violation(
            options = question.options,
            finalAnswer = candidate.finalAnswer.orEmpty(),
            choiceType = choiceType,
        )
    if (problem != null || candidate.steps.isEmpty()) {
        warnings.add(problem ?: "按题型重答后仍没有可用的解答步骤，已保留原答案。")
        return ChoiceEnforcement(usage = retried.usage)
    }
    return ChoiceEnforcement(raw = retried.raw, solution = candidate, usage = retried.usage)
}
