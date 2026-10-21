package com.totop.app.domain.model

/**
 * 每日汇总（开发方案 5.1 `daily_stats`，主键 (date, subject)）。
 *
 * [dateEpochDayUtc] 为 UTC 日序号（自 1970-01-01 起的天数），与时区无关。
 */
data class DailyStats(
    val dateEpochDayUtc: Long,
    val subject: String,
    /** 当日新增错题数。 */
    val mistakeCount: Int = 0,
    /** 当日涉及知识点数。 */
    val kpointCount: Int = 0,
    /** 当日提示次数。 */
    val hintCount: Int = 0,
    /** 当日完成复习次数。 */
    val reviewDoneCount: Int = 0,
    /** 当日学习时长（秒）。 */
    val studySeconds: Long = 0L,
) {
    init {
        require(mistakeCount >= 0 && kpointCount >= 0 && hintCount >= 0 && reviewDoneCount >= 0) {
            "daily_stats 计数字段不得为负"
        }
        require(studySeconds >= 0) { "studySeconds 不得为负" }
    }
}

/** 复习记录（开发方案 5.1 `review_log`），以学生自评结果记录复习效果。 */
data class ReviewLog(
    val id: String,
    val mistakeId: String,
    /** 复习时间（UTC 毫秒）。 */
    val reviewedAtUtc: Long,
    /** 自评结果。 */
    val selfResult: RecallSelfResult,
)

/**
 * 提示记录（开发方案 5.1 `hint_log`，level 1..3）。
 * 提示级别用于掌握度的「全靠提示想通」惩罚判定。
 */
data class HintLog(
    val id: String,
    val mistakeId: String,
    /** 提示级别 1..3。 */
    val level: Int,
    /** 提示时间（UTC 毫秒）。 */
    val createdAtUtc: Long,
) {
    init {
        require(level in 1..3) { "提示级别必须为 1..3，实际为：$level" }
    }
}

/** 生成练习题（开发方案 5.1 `exercise`）。 */
data class Exercise(
    val id: String,
    /** 受控知识点 ID。 */
    val kpointId: String,
    val stem: String,
    val answer: String,
    val difficulty: ExerciseDifficulty,
    /** 是否通过独立重解校验（FR-9.2）。 */
    val verified: Boolean = false,
    /** 创建时间（UTC 毫秒）。 */
    val createdAtUtc: Long,
) {
    val hasControlledKpoint: Boolean get() = KnowledgeIdConvention.isValid(kpointId)
}

/** AI 调用追踪（开发方案 5.1 `ai_trace`），用于成本与成功率统计。 */
data class AiTrace(
    val id: String,
    /** 客户端幂等请求 ID。 */
    val requestId: String,
    /** 阶段 A/B/C/D。 */
    val stage: AiStage,
    val model: String,
    val promptVer: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0L,
    val ok: Boolean = true,
    val errorMsg: String? = null,
    /** 调用时间（UTC 毫秒）。 */
    val createdAtUtc: Long,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}
