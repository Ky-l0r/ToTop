package com.totop.app.domain.usecase.port

import com.totop.app.domain.model.AiTrace
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.ReviewLog

/*
 * 领域层端口：本地持久化能力。
 *
 * 这些接口由 data 层的 Repository 适配实现（方法名基本一一对应，适配层只需转发），
 * 领域层因此不出现 Room / Entity / DAO 类型，UseCase 可在 JVM 单测中用假实现驱动。
 *
 * 分片理由：按聚合拆成多个窄接口（而不是一个大而全的存储接口），
 * 这样单测里只需要实现自己用到的那一两个，假实现不会被迫写一堆 `TODO()`。
 */

/** 题目存取。 */
interface QuestionStore {
    suspend fun byId(id: String): Question?

    /** 按图片摘要查重（question.imageSha256 唯一）。 */
    suspend fun byImageSha256(sha256: String): Question?

    /**
     * 去重保存：sha256 已存在时返回已有题目而不是新增。
     *
     * @return 落库后的题目与是否新建
     */
    suspend fun saveDeduplicated(question: Question): SavedQuestion

    /** 保存结果。 */
    data class SavedQuestion(val question: Question, val createdNew: Boolean)
}

/** 错题存取（含知识点归属，写操作必须是原子的）。 */
interface MistakeStore {
    suspend fun byId(id: String): MistakeWithKpoints?

    suspend fun byQuestionId(questionId: String): List<MistakeRecord>

    /**
     * 原子保存「错题 + 知识点归属」。
     *
     * @param recomputeRecurrence true 时按同知识点历史出错次数重算 recurrenceIndex（FR-4.5 复发提示）
     */
    suspend fun save(
        record: MistakeRecord,
        assignments: List<KpointAssignment> = emptyList(),
        recomputeRecurrence: Boolean = false,
    ): MistakeRecord

    /** 最近的错题（导出用；必须带 limit，禁止全表拉取）。 */
    suspend fun recent(limit: Int): List<MistakeRecord>

    /** [fromUtc, toUtc) 区间内的错题（导出用）。 */
    suspend fun rangeOf(
        fromUtc: Long,
        toUtc: Long,
        limit: Int = 500,
    ): List<MistakeRecord>

    /** 某错题的知识点 ID 列表。 */
    suspend fun kpointsOfMistake(mistakeId: String): List<String>

    /** 某知识点在 [fromUtc, toUtc) 内的错题次数（掌握度与周报的输入）。 */
    suspend fun countCreatedBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Int

    /** 某知识点在 [fromUtc, toUtc) 内的错题时间戳列表，按时间升序。 */
    suspend fun mistakeTimesOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): List<Long>

    /** 错因分布：errorType.code -> 次数。 */
    suspend fun errorTypeDistribution(
        fromUtc: Long,
        toUtc: Long,
    ): Map<String, Int>

    /** 知识点分布：kpointId -> 次数。 */
    suspend fun kpointDistribution(
        fromUtc: Long,
        toUtc: Long,
    ): Map<String, Int>

    /** 错题 + 其知识点 ID。 */
    data class MistakeWithKpoints(val mistake: MistakeRecord, val kpointIds: List<String>)

    /** 某知识点下全部错题的 ID（掌握度聚合用）。 */
    suspend fun mistakeIdsOfKpoint(kpointId: String): List<String>

    /** 复习记录（自评驱动间隔重复，FR-4.2）。 */
    suspend fun addReview(log: ReviewLog)

    suspend fun reviewsOf(mistakeId: String): List<ReviewLog>

    /**
     * 某知识点下全部错题在 [fromUtc, toUtc) 内的复习记录（掌握度的 recall 项输入）。
     *
     * 必须走 mistake_kpoint 关联查询，禁止全表扫描（开发方案 5.3 性能要求）。
     */
    suspend fun reviewsOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): List<ReviewLog>

    suspend fun reviewCountBetween(
        fromUtc: Long,
        toUtc: Long,
        selfResultWire: String,
    ): Int
}

/** 知识点与图谱。 */
interface KpointDirectory {
    /** 全量知识点。 */
    suspend fun allKnowledgePoints(): List<KnowledgePoint>

    suspend fun knowledgePoint(id: String): KnowledgePoint?

    /** 全量边（prerequisite / related / extends）。 */
    suspend fun edges(): List<KpointEdge>

    /** 按名称精确查受控 ID（阶段 D 的候选清单回填用）。 */
    suspend fun idByName(name: String): String?

    /**
     * 按题干文本召回候选知识点（阶段 D 的候选清单，开发方案 6.3「题目 + 候选知识点清单」）。
     *
     * 实现约束：必须来自内置受控树，禁止自由造词；返回数量由实现裁剪（建议 <= 40）。
     */
    suspend fun candidatesFor(
        stemText: String,
        subject: String,
        limit: Int = 40,
    ): List<KnowledgePoint>

    /** 某知识点的前置 ID（只有 prerequisite 边）。 */
    suspend fun prerequisiteIdsOf(kpointId: String): List<String>
}

/** 掌握度存取（含历史快照）。 */
interface MasteryStore {
    suspend fun masteryOf(kpointId: String): Mastery?

    suspend fun allMastery(): List<Mastery>

    /** 保存单个掌握度（实现负责并入历史快照并裁剪到 90 天）。 */
    suspend fun save(mastery: Mastery): Mastery

    /** 批量保存。 */
    suspend fun saveAll(items: List<Mastery>): List<Mastery>
}

/** 日汇总存取（禁止全表扫描：只按日期区间取）。 */
interface DailyStatsStore {
    /** 区间 [fromDateEpochDay, toDateEpochDay] 的日汇总。 */
    suspend fun between(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): List<DailyStats>

    suspend fun sumMistakeCount(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Int

    suspend fun sumReviewDoneCount(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Int

    suspend fun sumStudySeconds(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Long
}

/** 提示记录（参与掌握度的「全靠提示」判定）。 */
interface HintStore {
    suspend fun hintsOf(mistakeId: String): List<com.totop.app.domain.model.HintLog>

    /**
     * 某知识点下错题在 [fromUtc, toUtc) 内被解锁的提示次数。
     *
     * 用「该知识点错题的提示」而不是全局提示数：否则每道题的提示都会惩罚所有知识点。
     */
    suspend fun hintCountOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): Int
}

/** ai_trace 写入与查询（开发方案 6.2：每次调用都写）。 */
interface TraceStore {
    suspend fun insert(trace: AiTrace)

    suspend fun between(
        fromUtc: Long,
        toUtc: Long,
    ): List<AiTrace>

    suspend fun byRequestId(requestId: String): List<AiTrace>
}

/** 复习排期持久化（SM-2 结论落库，WorkManager 据此排期）。 */
interface ReviewScheduleStore {
    /** 写入某错题的复习计划（下次复习时间）。 */
    suspend fun schedule(
        mistakeId: String,
        dueAtUtc: Long,
        intervalDays: Int,
        easiness: Double,
    )

    suspend fun dueBefore(
        nowUtc: Long,
        limit: Int = 50,
    ): List<String>
}

/**
 * 掌握度历史裁剪端口（实现：data 层 MasteryHistory，默认保留 90 天）。
 *
 * 注意：SAM 接口的方法不能带默认参数值（Kotlin 限制），因此默认天数由调用方传入，
 * 默认值常量放在 [MasteryHistoryTrimmer.DEFAULT_KEEP_DAYS]。
 */
fun interface MasteryHistoryTrimmer {
    fun trim(
        history: List<MasterySnapshot>,
        keepDays: Int,
    ): List<MasterySnapshot>

    companion object {
        /** 默认保留天数：90 天（开发方案 5.2）。 */
        const val DEFAULT_KEEP_DAYS: Int = 90

        /** 按日序号裁剪的默认实现（保留最近 keepDays 天）。 */
        fun byEpochDay(): MasteryHistoryTrimmer =
            MasteryHistoryTrimmer { history, keepDays ->
                if (history.isEmpty()) {
                    history
                } else {
                    val newest = history.maxOf { it.dateEpochDayUtc }
                    val cutoff = newest - keepDays.toLong() + 1L
                    history.filter { it.dateEpochDayUtc >= cutoff }
                }
            }
    }
}

/**
 * 一次解题的落库草案（开发方案 3.2 第 [10] 步）。
 *
 * 四张表必须**原子**写入：question / mistake_record / mistake_kpoint / （ai_trace 由网关负责）。
 * 如果分成四次独立写入，中途失败就会留下「题目入库但错题缺失」「错题入库但知识点缺失」的脏数据，
 * 而脏数据会直接污染掌握度统计（方案 6.2「禁止让 AI 的错误输出污染知识点统计」）。
 *
 * 因此这里只暴露一个原子方法，由 data 层用 Room 的 withTransaction 实现。
 */
data class SolvedDraft(
    val question: Question,
    /** 错题记录；学生全对或未定位到错因时为 null（题目仍入库，但不在错题本展示）。 */
    val mistake: MistakeRecord?,
    /** 知识点归属（mistake_kpoint）。 */
    val assignments: List<KpointAssignment>,
    /** 是否按同知识点历史出错次数重算 recurrenceIndex（FR-4.5）。 */
    val recomputeRecurrence: Boolean = true,
)

/** 原子落库结果。 */
data class PersistedSolve(
    val question: Question,
    /** 落库后的错题（可能因去重返回已有记录）。 */
    val mistake: MistakeRecord?,
    /** 题目是否为本次新建（false 表示命中了已有 sha256）。 */
    val questionCreatedNew: Boolean,
)

/**
 * 解题结果原子写入端口（实现：data 层，一个 Room 事务内完成三表写入）。
 *
 * 契约：要么三表全部写入成功，要么全部回滚并抛出异常；
 * 调用方（[com.totop.app.domain.usecase.SolveQuestionUseCase]）捕获异常后返回失败，绝不返回「部分成功」。
 */
interface SolvedRecordWriter {
    suspend fun persistAtomically(draft: SolvedDraft): PersistedSolve
}
