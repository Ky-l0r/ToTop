package com.totop.app.domain.usecase.port

import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty

/*
 * 领域层端口：报告、练习与导出。
 */

/** 报告持久化（report 表：statsJson 与 aiComment 分开存）。 */
interface ReportStore {
    /**
     * 保存报告。
     *
     * @param id 报告主键
     * @param type weekly / monthly
     * @param periodKey 周期键，例如 2025-W03
     * @param statsJson 纯统计 JSON（模板渲染的数字来源，可审计）
     * @param aiComment AI 解读；关闭 AI 解读时为 null（此时为「纯统计版」）
     * @param createdAtUtc UTC 毫秒
     */
    suspend fun save(
        id: String,
        type: String,
        periodKey: String,
        statsJson: String,
        aiComment: String?,
        createdAtUtc: Long,
    )

    /** 查询某周期报告；不存在返回 null。 */
    suspend fun byPeriod(
        type: String,
        periodKey: String,
    ): ReportRecord?

    /** 报告投影。 */
    data class ReportRecord(
        val id: String,
        val type: String,
        val periodKey: String,
        val statsJson: String,
        val aiComment: String?,
        val createdAtUtc: Long,
    )
}

/** 练习题存取（verified=1 才允许展示与复习，FR-9.2）。 */
interface ExerciseStore {
    suspend fun verifiedOfKpoint(kpointId: String): List<Exercise>

    suspend fun save(exercise: Exercise)

    suspend fun saveAll(exercises: List<Exercise>)

    suspend fun countVerified(): Int
}

/** 一次变式题生成请求。 */
data class ExerciseRequest(
    /** 受控知识点 ID（必须满足 KnowledgeIdConvention）。 */
    val kpointId: String,
    val kpointName: String,
    val difficulty: ExerciseDifficulty,
    /** 期望条数；实现必须裁剪到合理上限（建议 <= 5）。 */
    val count: Int = 1,
    /** 参考原型题干（可选，通常是该知识点下用户最近的一道错题）。 */
    val sourceStem: String = "",
    val maxTokens: Int = 2048,
)

/** 模型返回的一道候选题（尚未校验）。 */
data class DraftExercise(
    val stem: String,
    val answer: String,
    val rationale: String = "",
)

/** 生成阶段的输出。 */
sealed interface ExerciseDraftOutcome {
    data class Success(val drafts: List<DraftExercise>, val usage: TokenUsageCount) : ExerciseDraftOutcome

    data class Failure(val userMessage: String, val retryable: Boolean = false) : ExerciseDraftOutcome
}

/** 独立重解校验的结果。 */
sealed interface VerificationOutcome {
    /**
     * 校验完成。
     *
     * @param consistent 独立重解的答案是否与生成答案一致（一致才允许入库）
     * @param derivedAnswer 独立重解得到的答案；无法解析时为 null
     */
    data class Success(
        val consistent: Boolean,
        val derivedAnswer: String?,
        val usage: TokenUsageCount = TokenUsageCount.EMPTY,
    ) : VerificationOutcome

    /** 校验调用失败：**不得**把该题标记为 verified。 */
    data class Failure(val userMessage: String, val retryable: Boolean = false) : VerificationOutcome
}

/**
 * 练习题生成端口（实现：data/remote，复用 AiGateway 的模型与提示词能力）。
 *
 * 生成与校验是两次**独立**调用（FR-9.2：生成后另起一次调用让模型独立重解），
 * 因此拆成两个方法，UseCase 负责编排「生成 -> 独立重解 -> 一致才入库」。
 */
interface ExerciseGenerator {
    /** 生成候选题（不校验、不入库）。 */
    suspend fun generate(
        request: ExerciseRequest,
        requestId: String,
    ): ExerciseDraftOutcome

    /**
     * 独立重解校验。
     *
     * @param stem 题干
     * @param claimedAnswer 生成阶段给出的答案（不得泄漏给重解调用，实现方只传题干）
     */
    suspend fun verify(
        stem: String,
        claimedAnswer: String,
        requestId: String,
    ): VerificationOutcome
}

/** 报告 AI 解读端口（可关闭：关闭时输出纯统计版，FR-5.3 / FR-5.4）。 */
interface ReportNarrator {
    /**
     * 生成约 200 字的解读与建议。
     *
     * @param statsJson 纯统计 JSON（数字必须已由模板渲染，AI 只写文字，不得改数字）
     * @return 解读文本；调用失败返回 [NarrativeOutcome.Failure]，由 UseCase 降级为纯统计版
     */
    suspend fun narrate(
        statsJson: String,
        requestId: String,
    ): NarrativeOutcome

    sealed interface NarrativeOutcome {
        data class Success(val text: String, val usage: TokenUsageCount = TokenUsageCount.EMPTY) : NarrativeOutcome

        data class Failure(val userMessage: String) : NarrativeOutcome
    }
}

/** 导出格式（开发方案 FR-10）。 */
enum class ExportFormat(val wire: String, val label: String, val extension: String, val mimeType: String) {
    MARKDOWN("markdown", "Markdown 错题本", "md", "text/markdown"),
    CSV("csv", "CSV 明细", "csv", "text/csv"),
    ANKI("anki", "Anki 卡片", "csv", "text/csv"),
    BACKUP_JSON("backup_json", "全量备份", "json", "application/json"),
}

/** 导出数据类别（CSV 三个视图，FR-10.2）。 */
enum class ExportView(val wire: String, val label: String) {
    MISTAKES("mistakes", "错题明细"),
    KPOINT_STATS("kpoint_stats", "知识点统计"),
    WEEKLY_REPORT("weekly_report", "周报数据"),
}

/** 一次导出请求。 */
data class ExportRequest(
    val format: ExportFormat,
    /** MARKDOWN / CSV / ANKI 使用；BACKUP_JSON 忽略。 */
    val view: ExportView = ExportView.MISTAKES,
    /** 只导出 [fromUtc, toUtc) 区间；为 null 表示全部。 */
    val fromUtc: Long? = null,
    val toUtc: Long? = null,
    /** 是否包含 AI 生成内容的标识（FR-10.6）。 */
    val includeAiGeneratedFlag: Boolean = true,
) {
    init {
        require(fromUtc == null || toUtc == null || fromUtc <= toUtc) {
            "导出区间非法：fromUtc 不得晚于 toUtc"
        }
    }
}

/** 导出产物：已渲染好的文本内容（写盘由实现方用 SAF / MediaStore 完成）。 */
data class ExportArtifact(
    val format: ExportFormat,
    val view: ExportView,
    /** 建议文件名（不含目录），例如 `错题本_20250101.md`。 */
    val suggestedFileName: String,
    val mimeType: String,
    val content: String,
) {
    init {
        require(suggestedFileName.isNotBlank()) { "导出文件名不得为空" }
    }

    /** 内容字节数（估算外置存储占用）。 */
    val sizeBytes: Long get() = content.toByteArray(Charsets.UTF_8).size.toLong()
}

/** 导出结果。 */
sealed interface ExportOutcome {
    data class Success(val artifact: ExportArtifact) : ExportOutcome

    /** 导出失败（数据为空、格式不支持、渲染异常）。 */
    data class Failure(val userMessage: String, val detail: String? = null) : ExportOutcome
}

/**
 * 数据导出端口（开发方案 FR-10）。
 *
 * 领域层只定义「导出什么、产出什么」，**具体文件写入（SAF / MediaStore / FileProvider）
 * 由 ml 成员在实现类中完成**，因此这里不出现任何 Android 文件 API 类型。
 * 渲染本身（Markdown / CSV / Anki 文本拼装）由 [com.totop.app.domain.usecase.ExportDataUseCase] 负责，
 * 实现方只需把 [ExportArtifact.content] 写到用户选择的位置。
 */
interface DataExporter {
    /** 支持的格式（实现可裁剪，但必须至少支持 [ExportFormat.MARKDOWN]）。 */
    fun supportedFormats(): Set<ExportFormat>

    /**
     * 执行导出。
     *
     * @param artifact 已渲染好的产物
     * @param destinationUri 用户通过 SAF 选择的目标 Uri；为 null 时由实现决定默认位置
     */
    suspend fun write(
        artifact: ExportArtifact,
        destinationUri: String? = null,
    ): ExportWriteResult
}

/** 写盘结果。 */
sealed interface ExportWriteResult {
    data class Success(val location: String, val sizeBytes: Long) : ExportWriteResult

    data class Failure(val userMessage: String, val detail: String? = null) : ExportWriteResult
}
