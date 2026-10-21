package com.totop.app.domain.usecase

import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.usecase.port.DataExporter
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.ExportArtifact
import com.totop.app.domain.usecase.port.ExportFormat
import com.totop.app.domain.usecase.port.ExportOutcome
import com.totop.app.domain.usecase.port.ExportRequest
import com.totop.app.domain.usecase.port.ExportView
import com.totop.app.domain.usecase.port.ExportWriteResult
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MasteryStore
import com.totop.app.domain.usecase.port.MistakeStore
import com.totop.app.domain.usecase.port.QuestionStore
import com.totop.app.domain.usecase.port.ReportStore
import com.totop.app.domain.usecase.port.epochDayOf

/** 导出所需的一条错题（题目 + 错题 + 知识点名称）。 */
data class ExportedMistake(
    val question: Question,
    val record: MistakeRecord,
    val kpointNames: List<String>,
)

/**
 * 数据导出用例（开发方案 FR-10）。
 *
 * 分工：
 * - **本类负责「渲染」**：把领域数据拼成 Markdown / CSV / Anki / 备份 JSON（纯逻辑，可单测）；
 * - **写盘由 [DataExporter] 的实现完成**（ml 成员用 SAF / MediaStore），
 *   因此本类不出现任何 Android 文件 API，可在 JVM 单测中完整验证。
 *
 * 约束：
 * - Markdown 里的公式保持 Unicode / LaTeX 原文，不做「口语化」替换（导出是给人看的，不是给 TTS 念的）；
 * - AI 生成内容必须保留标识（FR-10.6），避免用户把 AI 内容当成人工整理；
 * - CSV 必须转义（引号、逗号、换行），否则表格会错列；
 * - 所有时间为 UTC 毫秒，表头注明，避免跨时区歧义。
 */
class ExportDataUseCase(
    private val questions: QuestionStore,
    private val mistakes: MistakeStore,
    private val kpoints: KpointDirectory,
    private val masteries: MasteryStore,
    private val reports: ReportStore,
    private val exporter: DataExporter,
    private val clock: DomainClock = DomainClock.SYSTEM,
) {
    /**
     * 渲染并写盘。
     *
     * @param destinationUri 用户通过 SAF 选择的目标；为 null 时由实现决定默认位置
     */
    suspend fun export(
        request: ExportRequest,
        destinationUri: String? = null,
    ): ExportOutcome {
        val artifact =
            when (val rendered = render(request)) {
                is ExportOutcome.Success -> rendered.artifact
                is ExportOutcome.Failure -> return rendered
            }
        return when (val write = exporter.write(artifact, destinationUri)) {
            is ExportWriteResult.Success -> ExportOutcome.Success(artifact)

            is ExportWriteResult.Failure ->
                ExportOutcome.Failure(
                    userMessage = write.userMessage,
                    detail = write.detail,
                )
        }
    }

    /** 只渲染不写盘（预览 / 单测）。 */
    suspend fun render(request: ExportRequest): ExportOutcome {
        val supported = runCatching { exporter.supportedFormats() }.getOrDefault(setOf(ExportFormat.MARKDOWN))
        if (request.format !in supported) {
            return ExportOutcome.Failure(
                userMessage = "当前版本还不支持导出为「" + request.format.label + "」，请改用 Markdown。",
            )
        }
        return when (request.format) {
            ExportFormat.MARKDOWN -> renderMarkdown(request)
            ExportFormat.CSV -> renderCsv(request)
            ExportFormat.ANKI -> renderAnki(request)
            ExportFormat.BACKUP_JSON -> renderBackup(request)
        }
    }

    // ---------------- Markdown ----------------

    private suspend fun renderMarkdown(request: ExportRequest): ExportOutcome {
        val items = loadMistakes(request)
        if (items.isEmpty()) {
            return ExportOutcome.Failure(userMessage = "没有可导出的错题。先拍一道题并定位错因吧。")
        }
        val builder = StringBuilder()
        builder.append("# To Top 错题本").append(NL).append(NL)
        builder.append("> 导出区间（UTC 毫秒）：").append(rangeText(request)).append(NL)
        builder.append("> 公式保持原始记法（Unicode/LaTeX），可直接粘贴到支持公式的编辑器。").append(NL).append(NL)
        items.forEachIndexed { index, item ->
            builder.append("## ").append(index + 1).append(". ")
                .append(item.question.stemText.take(HEADLINE_CHARS)).append(NL).append(NL)
            builder.append("- 学科：").append(item.question.subject)
                .append("｜学段：").append(item.question.grade).append(NL)
            if (item.question.known.isNotEmpty()) {
                builder.append("- 已知：").append(item.question.known.joinToString("；")).append(NL)
            }
            if (item.question.ask.isNotBlank()) {
                builder.append("- 所求：").append(item.question.ask).append(NL)
            }
            builder.append("- 错因：").append(item.record.errorType.label)
            item.record.firstErrorStepIdx?.let { stepIdx ->
                builder.append("（第 ").append(stepIdx).append(" 步）")
            }
            builder.append(NL)
            if (item.record.errorExplain.isNotBlank()) {
                builder.append("- 错因说明：").append(item.record.errorExplain).append(NL)
            }
            if (item.kpointNames.isNotEmpty()) {
                builder.append("- 知识点：").append(item.kpointNames.joinToString("、")).append(NL)
            }
            if (item.record.correctSolution.steps.isNotEmpty()) {
                builder.append(NL).append("**正确解法（")
                    .append(item.record.correctSolution.method.ifBlank { "通用思路" })
                    .append("）**").append(NL).append(NL)
                item.record.correctSolution.steps.forEach { step -> builder.append(stepLine(step)) }
                if (item.record.correctSolution.finalAnswer.isNotBlank()) {
                    builder.append(NL).append("**答案**：")
                        .append(item.record.correctSolution.finalAnswer).append(NL)
                }
            }
            if (request.includeAiGeneratedFlag) {
                builder.append(NL).append("> 本题解法由 AI 生成，仅供参考。").append(NL)
            }
            builder.append(NL)
        }
        return ExportOutcome.Success(
            ExportArtifact(
                format = ExportFormat.MARKDOWN,
                view = request.view,
                suggestedFileName = "错题本_" + fileStamp() + ".md",
                mimeType = ExportFormat.MARKDOWN.mimeType,
                content = builder.toString(),
            ),
        )
    }

    // ---------------- CSV ----------------

    private suspend fun renderCsv(request: ExportRequest): ExportOutcome =
        when (request.view) {
            ExportView.MISTAKES -> {
                val items = loadMistakes(request)
                if (items.isEmpty()) {
                    ExportOutcome.Failure(userMessage = "没有可导出的错题明细。")
                } else {
                    val builder = StringBuilder()
                    builder.append(
                        csvRow(
                            listOf(
                                "题干",
                                "学科",
                                "学段",
                                "错因",
                                "首个错误步骤",
                                "错因说明",
                                "知识点",
                                "正确答案",
                                "创建时间(UTC毫秒)",
                            ),
                        ),
                    )
                    items.forEach { item ->
                        builder.append(
                            csvRow(
                                listOf(
                                    item.question.stemText,
                                    item.question.subject,
                                    item.question.grade,
                                    item.record.errorType.label,
                                    (item.record.firstErrorStepIdx ?: "").toString(),
                                    item.record.errorExplain,
                                    item.kpointNames.joinToString("、"),
                                    item.record.finalAnswer,
                                    item.record.createdAtUtc.toString(),
                                ),
                            ),
                        )
                    }
                    ExportOutcome.Success(csvArtifact(request, "错题明细", builder.toString()))
                }
            }

            ExportView.KPOINT_STATS -> {
                val all = runCatching { masteries.allMastery() }.getOrDefault(emptyList())
                if (all.isEmpty()) {
                    ExportOutcome.Failure(userMessage = "还没有掌握度数据，先做几道题再导出。")
                } else {
                    val names =
                        runCatching { kpoints.allKnowledgePoints() }
                            .getOrDefault(emptyList())
                            .associate { it.id to it.name }
                    val builder = StringBuilder()
                    builder.append(csvRow(listOf("知识点ID", "知识点名称", "掌握度", "色阶", "更新时间(UTC毫秒)")))
                    all.sortedBy { it.score }.forEach { mastery ->
                        builder.append(
                            csvRow(
                                listOf(
                                    mastery.kpointId,
                                    names[mastery.kpointId] ?: mastery.kpointId,
                                    formatScore(mastery.score),
                                    mastery.band.label,
                                    mastery.updatedAtUtc.toString(),
                                ),
                            ),
                        )
                    }
                    ExportOutcome.Success(csvArtifact(request, "知识点统计", builder.toString()))
                }
            }

            ExportView.WEEKLY_REPORT -> {
                val record = runCatching { reports.byPeriod("weekly", currentPeriodKey()) }.getOrNull()
                if (record == null) {
                    ExportOutcome.Failure(userMessage = "本期还没有生成周报，先去学情页生成一次。")
                } else {
                    val builder = StringBuilder()
                    builder.append(csvRow(listOf("字段", "值")))
                    builder.append(csvRow(listOf("周期", record.periodKey)))
                    builder.append(csvRow(listOf("统计数据(JSON)", record.statsJson)))
                    builder.append(csvRow(listOf("AI解读", record.aiComment ?: "未启用")))
                    builder.append(csvRow(listOf("生成时间(UTC毫秒)", record.createdAtUtc.toString())))
                    ExportOutcome.Success(csvArtifact(request, "周报数据", builder.toString()))
                }
            }
        }

    // ---------------- Anki ----------------

    private suspend fun renderAnki(request: ExportRequest): ExportOutcome {
        val items = loadMistakes(request)
        if (items.isEmpty()) {
            return ExportOutcome.Failure(userMessage = "没有可导出的错题卡片。")
        }
        val builder = StringBuilder()
        builder.append("#separator:tab").append(NL)
        builder.append("#html:true").append(NL)
        builder.append("#columns:正面\t背面").append(NL)
        items.forEach { item ->
            val front = escapeAnki(item.question.stemText)
            val back =
                buildString {
                    append("错因：").append(item.record.errorType.label).append("<br>")
                    if (item.record.errorExplain.isNotBlank()) {
                        append("说明：").append(escapeAnki(item.record.errorExplain)).append("<br>")
                    }
                    item.record.correctSolution.steps.forEach { step ->
                        append(escapeAnki(step.content)).append("<br>")
                    }
                    if (item.record.finalAnswer.isNotBlank()) {
                        append("<b>答案：").append(escapeAnki(item.record.finalAnswer)).append("</b><br>")
                    }
                    if (item.kpointNames.isNotEmpty()) {
                        append("知识点：").append(escapeAnki(item.kpointNames.joinToString("、")))
                    }
                    if (request.includeAiGeneratedFlag) append("<br><i>解法由 AI 生成</i>")
                }
            builder.append(front).append('\t').append(back).append(NL)
        }
        return ExportOutcome.Success(
            ExportArtifact(
                format = ExportFormat.ANKI,
                view = request.view,
                suggestedFileName = "Anki卡片_" + fileStamp() + ".txt",
                mimeType = ExportFormat.ANKI.mimeType,
                content = builder.toString(),
            ),
        )
    }

    // ---------------- 全量备份 ----------------

    private suspend fun renderBackup(request: ExportRequest): ExportOutcome {
        val items = loadMistakes(ExportRequest(ExportFormat.MARKDOWN, ExportView.MISTAKES))
        val masteryList = runCatching { masteries.allMastery() }.getOrDefault(emptyList())
        if (items.isEmpty() && masteryList.isEmpty()) {
            return ExportOutcome.Failure(userMessage = "本地还没有数据可备份。")
        }
        val builder = StringBuilder()
        builder.append("{").append(NL)
        builder.append("  \"schemaVersion\": 1,").append(NL)
        builder.append("  \"exportedAtUtc\": ").append(clock.nowUtcMillis()).append(",").append(NL)
        builder.append("  \"note\": \"此文件仅包含结构化数据，图片请另行备份；所有时间为 UTC 毫秒\",").append(NL)
        builder.append("  \"mistakes\": [").append(NL)
        items.forEachIndexed { index, item ->
            builder.append("    {")
            builder.append("\"questionId\":").append(jsonString(item.question.id)).append(',')
            builder.append("\"stem\":").append(jsonString(item.question.stemText)).append(',')
            builder.append("\"subject\":").append(jsonString(item.question.subject)).append(',')
            builder.append("\"grade\":").append(jsonString(item.question.grade)).append(',')
            builder.append("\"errorType\":").append(jsonString(item.record.errorType.code)).append(',')
            builder.append("\"errorTypeLabel\":").append(jsonString(item.record.errorType.label)).append(',')
            builder.append("\"firstErrorStepIdx\":").append(item.record.firstErrorStepIdx ?: 0).append(',')
            builder.append("\"finalAnswer\":").append(jsonString(item.record.finalAnswer)).append(',')
            builder.append("\"createdAtUtc\":").append(item.record.createdAtUtc)
            builder.append("}")
            if (index != items.lastIndex) builder.append(',')
            builder.append(NL)
        }
        builder.append("  ],").append(NL)
        builder.append("  \"mastery\": [").append(NL)
        masteryList.forEachIndexed { index, mastery ->
            builder.append("    {\"kpointId\":").append(jsonString(mastery.kpointId))
                .append(",\"score\":").append(formatScore(mastery.score))
                .append(",\"updatedAtUtc\":").append(mastery.updatedAtUtc)
            builder.append("}")
            if (index != masteryList.lastIndex) builder.append(',')
            builder.append(NL)
        }
        builder.append("  ]").append(NL).append("}").append(NL)
        return ExportOutcome.Success(
            ExportArtifact(
                format = ExportFormat.BACKUP_JSON,
                view = request.view,
                suggestedFileName = "ToTop备份_" + fileStamp() + ".json",
                mimeType = ExportFormat.BACKUP_JSON.mimeType,
                content = builder.toString(),
            ),
        )
    }

    // ---------------- 公共辅助 ----------------

    private suspend fun loadMistakes(request: ExportRequest): List<ExportedMistake> {
        val records =
            runCatching {
                if (request.fromUtc != null || request.toUtc != null) {
                    mistakes.rangeOf(request.fromUtc ?: 0L, request.toUtc ?: Long.MAX_VALUE, EXPORT_LIMIT)
                } else {
                    mistakes.recent(EXPORT_LIMIT)
                }
            }.getOrDefault(emptyList())
        if (records.isEmpty()) return emptyList()
        val names =
            runCatching { kpoints.allKnowledgePoints() }
                .getOrDefault(emptyList())
                .associate { it.id to it.name }
        return records.map { record ->
            val question =
                runCatching { questions.byId(record.questionId) }.getOrNull()
                    ?: Question(
                        id = record.questionId,
                        subject = "math",
                        grade = "未知",
                        stemText = "（原题已删除）",
                        createdAtUtc = record.createdAtUtc,
                    )
            val kpointIds = runCatching { mistakes.kpointsOfMistake(record.id) }.getOrDefault(emptyList())
            ExportedMistake(
                question = question,
                record = record,
                kpointNames = kpointIds.map { names[it] ?: it },
            )
        }
    }

    private fun stepLine(step: SolutionStep): String {
        val why = if (step.why.isNotBlank()) "（为什么：" + step.why + "）" else ""
        return step.idx.toString() + ". " + step.content + why + NL
    }

    private fun csvArtifact(
        request: ExportRequest,
        name: String,
        content: String,
    ): ExportArtifact =
        ExportArtifact(
            format = ExportFormat.CSV,
            view = request.view,
            suggestedFileName = name + "_" + fileStamp() + ".csv",
            mimeType = ExportFormat.CSV.mimeType,
            content = content,
        )

    /** CSV 行：每个字段用双引号包裹，内部引号转义为两个引号。 */
    fun csvRow(fields: List<String>): String =
        fields.joinToString(",") { field -> "\"" + field.replace("\"", "\"\"") + "\"" } + NL

    private fun escapeAnki(text: String): String = text.replace("\t", " ").replace("\r", "").replace("\n", "<br>")

    /** JSON 字符串转义（备份文件必须能被标准解析器读取）。 */
    private fun jsonString(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private fun formatScore(score: Double): String = (Math.round(score * 10.0).toDouble() / 10.0).toString()

    private fun rangeText(request: ExportRequest): String {
        val from = request.fromUtc?.toString() ?: "最早"
        val to = request.toUtc?.toString() ?: "现在"
        return from + " ~ " + to
    }

    private fun fileStamp(): String = "d" + epochDayOf(clock.nowUtcMillis())

    private fun currentPeriodKey(): String = "w" + epochDayOf(clock.nowUtcMillis()) / 7

    companion object {
        /** 单次导出最多包含多少条错题（防止生成超大文件）。 */
        const val EXPORT_LIMIT: Int = 500

        /** Markdown 标题里题干截断长度。 */
        const val HEADLINE_CHARS: Int = 60

        /** 换行符（统一 LF）。 */
        private const val NL: String = "\n"
    }
}
