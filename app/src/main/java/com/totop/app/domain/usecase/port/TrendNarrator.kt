package com.totop.app.domain.usecase.port

/** 趋势解读端口（学情页 AI 解读，手动触发）。 */
interface TrendNarrator {
    /**
     * 根据本地已算好的结构化摘要写一段解读。
     *
     * @param summaryJson 纯摘要 JSON（数字全部由本机计算，AI 只写文字，不得改写数字）
     */
    suspend fun narrate(
        summaryJson: String,
        requestId: String,
    ): Outcome

    sealed interface Outcome {
        data class Success(
            val text: String,
            val usage: TokenUsageCount = TokenUsageCount.EMPTY,
        ) : Outcome

        data class Failure(val userMessage: String) : Outcome
    }
}
