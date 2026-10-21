package com.totop.app.ui.report

import com.totop.app.domain.usecase.ErrorTypeCount
import com.totop.app.domain.usecase.KpointCount
import com.totop.app.domain.usecase.ReportPeriod
import com.totop.app.domain.usecase.ReportStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 周报页的纯逻辑（FR-5）。
 *
 * 覆盖两件事：
 * 1. 报告正文的 Markdown 分块（长图/PDF 渲染的输入）解析稳定；
 * 2. 状态文案遵守文案约束：不出现比率式评分或评判性措辞，且 AI 关闭/失败时必须明说。
 */
class ReportScreenLogicTest {
    @Test
    @DisplayName("Markdown 分块：标题 / 小节 / 引用 / 正文四类，空行丢弃")
    fun parseMarkdownBlocks() {
        val markdown =
            """
            # 学情周报（2025-W03）

            > 本报告所有数字均由本机统计生成，不上传任何数据。

            ## 一、本期概览

            - 新增错题：12 道
            - 复习完成率：82%
            """.trimIndent()
        val blocks = ReportTextBlocks.parse(markdown)
        assertEquals(ReportTextBlocks.Kind.TITLE, blocks.first().kind)
        assertTrue(blocks.first().text.contains("2025-W03"))
        assertTrue(blocks.any { it.kind == ReportTextBlocks.Kind.QUOTE })
        assertTrue(blocks.any { it.kind == ReportTextBlocks.Kind.HEADING })
        assertTrue(blocks.any { it.text.contains("新增错题") })
        assertTrue(blocks.none { it.text.isBlank() })
    }

    @Test
    @DisplayName("纯文本化：去掉 Markdown 标记，保留全部正文行")
    fun plainTextKeepsContent() {
        val markdown = "# 标题\n## 小节\n- 一行内容"
        val text = ReportTextBlocks.toPlainText(markdown)
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("小节"))
        assertTrue(text.contains("一行内容"))
        assertFalse(text.contains("##"))
    }

    @Test
    @DisplayName("AI 解读失败降级：提示文案必须说明「纯统计版」")
    fun aiDegradedStatus() {
        val state = ReportUiState(aiEnabled = true, aiDegraded = true, markdown = "# x")
        assertTrue(state.aiStatusText.contains("纯统计版"))
        assertFalse(state.aiStatusText.contains(BANNED_RATIO_WORD))
    }

    @Test
    @DisplayName("AI 关闭：状态说明为纯统计版且 0 token")
    fun aiDisabledStatus() {
        val state = ReportUiState(aiEnabled = false, markdown = "# x")
        assertTrue(state.aiStatusText.contains("纯统计"))
    }

    @Test
    @DisplayName("AI 成功：状态必须标注「AI 生成」")
    fun aiSuccessStatus() {
        val state = ReportUiState(aiEnabled = true, aiComment = "本周建议先补导数。", markdown = "# x")
        assertTrue(state.aiStatusText.contains("AI 生成"))
    }

    @Test
    @DisplayName("报告标题随周期切换")
    fun titleByPeriod() {
        assertEquals("学情周报", ReportUiState(period = ReportPeriod.WEEKLY).title)
        assertEquals("学情月报", ReportUiState(period = ReportPeriod.MONTHLY).title)
    }

    @Test
    @DisplayName("六项指标排版不依赖 UI：学习时长与完成率文案来自 domain 模板")
    fun statsTextFromDomain() {
        val stats =
            ReportStats(
                type = "weekly",
                periodKey = "2025-W03",
                fromUtc = 0L,
                toUtc = 604_800_000L,
                newMistakeCount = 12,
                recurringKpoints = listOf(KpointCount("KP_MATH_0001", "导数与单调性", 3)),
                errorTypeDistribution = listOf(ErrorTypeCount("CALCULATION_ERROR", "计算失误", 5)),
                reviewCompletionRate = 0.82,
                reviewDoneCount = 9,
                studySeconds = 12_000L,
                activeDays = 5,
                periodDays = 7,
            )
        assertEquals("82%", stats.reviewRateText)
        assertEquals("3 小时 20 分", stats.studyDurationText)
        assertEquals(1, stats.topErrorTypes().size)
    }

    @Test
    @DisplayName("文件名清洗：非法字符替换为下划线，空名回退")
    fun sanitizeFileName() {
        assertEquals("a_b", ReportImageRenderer.sanitize("a/b"))
        assertEquals("report", ReportImageRenderer.sanitize("   "))
        assertTrue(ReportImageRenderer.sanitize("学情周报_2025-W03").contains("学情周报"))
    }

    @Test
    @DisplayName("导出格式：PNG 与 PDF 各有扩展名与 MIME")
    fun exportFormats() {
        assertEquals("png", ReportExportFormat.PNG.extension)
        assertEquals("image/png", ReportExportFormat.PNG.mimeType)
        assertEquals("pdf", ReportExportFormat.PDF.extension)
        assertEquals("application/pdf", ReportExportFormat.PDF.mimeType)
    }

    @Test
    @DisplayName("Markdown 中不允许出现被禁用的文案")
    fun forbiddenCopy() {
        val stats =
            ReportStats(
                type = "weekly",
                periodKey = "2025-W03",
                fromUtc = 0L,
                toUtc = 1L,
                newMistakeCount = 1,
                recurringKpoints = emptyList(),
                errorTypeDistribution = emptyList(),
                reviewCompletionRate = 1.0,
                reviewDoneCount = 0,
                studySeconds = 0L,
                activeDays = 0,
                periodDays = 7,
            )
        val useCase =
            com.totop.app.domain.usecase.BuildWeeklyReportUseCase(
                dailyStats = noopDailyStats,
                mistakes = noopMistakes,
                kpoints = noopKpoints,
                reports = noopReports,
                narrator = noopNarrator,
            )
        val markdown = useCase.renderMarkdown(stats, aiComment = null)
        assertFalse(markdown.contains(BANNED_RATIO_WORD))
        assertFalse(markdown.contains(BANNED_GRADING_WORD))
        assertTrue(markdown.contains("新增错题"))
    }

    private companion object {
        /** 被禁用的比率式评分词（分两段拼接，避免源码出现完整字面量）。 */
        const val BANNED_RATIO_WORD: String = "正确" + "率"

        /** 被禁用的评判性措辞。 */
        const val BANNED_GRADING_WORD: String = "判" + "卷"
    }

    private val noopDailyStats =
        object : com.totop.app.domain.usecase.port.DailyStatsStore {
            override suspend fun between(
                fromDateEpochDay: Long,
                toDateEpochDay: Long,
            ) = emptyList<com.totop.app.domain.model.DailyStats>()

            override suspend fun sumMistakeCount(
                fromDateEpochDay: Long,
                toDateEpochDay: Long,
            ) = 0

            override suspend fun sumReviewDoneCount(
                fromDateEpochDay: Long,
                toDateEpochDay: Long,
            ) = 0

            override suspend fun sumStudySeconds(
                fromDateEpochDay: Long,
                toDateEpochDay: Long,
            ) = 0L
        }

    private val noopMistakes =
        object : com.totop.app.domain.usecase.port.MistakeStore {
            override suspend fun byId(id: String) = null

            override suspend fun byQuestionId(questionId: String) =
                emptyList<com.totop.app.domain.model.MistakeRecord>()

            override suspend fun save(
                record: com.totop.app.domain.model.MistakeRecord,
                assignments: List<com.totop.app.domain.model.KpointAssignment>,
                recomputeRecurrence: Boolean,
            ) = record

            override suspend fun recent(limit: Int) = emptyList<com.totop.app.domain.model.MistakeRecord>()

            override suspend fun rangeOf(
                fromUtc: Long,
                toUtc: Long,
                limit: Int,
            ) = emptyList<com.totop.app.domain.model.MistakeRecord>()

            override suspend fun kpointsOfMistake(mistakeId: String) = emptyList<String>()

            override suspend fun countCreatedBetween(
                fromUtc: Long,
                toUtc: Long,
            ) = 0

            override suspend fun mistakeTimesOfKpoint(
                kpointId: String,
                fromUtc: Long,
                toUtc: Long,
            ) = emptyList<Long>()

            override suspend fun errorTypeDistribution(
                fromUtc: Long,
                toUtc: Long,
            ) = emptyMap<String, Int>()

            override suspend fun kpointDistribution(
                fromUtc: Long,
                toUtc: Long,
            ) = emptyMap<String, Int>()

            override suspend fun mistakeIdsOfKpoint(kpointId: String) = emptyList<String>()

            override suspend fun addReview(log: com.totop.app.domain.model.ReviewLog) = Unit

            override suspend fun reviewsOf(mistakeId: String) = emptyList<com.totop.app.domain.model.ReviewLog>()

            override suspend fun reviewsOfKpoint(
                kpointId: String,
                fromUtc: Long,
                toUtc: Long,
            ) = emptyList<com.totop.app.domain.model.ReviewLog>()

            override suspend fun reviewCountBetween(
                fromUtc: Long,
                toUtc: Long,
                selfResultWire: String,
            ) = 0
        }

    private val noopKpoints =
        object : com.totop.app.domain.usecase.port.KpointDirectory {
            override suspend fun allKnowledgePoints() = emptyList<com.totop.app.domain.model.KnowledgePoint>()

            override suspend fun knowledgePoint(id: String) = null

            override suspend fun edges() = emptyList<com.totop.app.domain.model.KpointEdge>()

            override suspend fun idByName(name: String) = null

            override suspend fun candidatesFor(
                stemText: String,
                subject: String,
                limit: Int,
            ) = emptyList<com.totop.app.domain.model.KnowledgePoint>()

            override suspend fun prerequisiteIdsOf(kpointId: String) = emptyList<String>()
        }

    private val noopReports =
        object : com.totop.app.domain.usecase.port.ReportStore {
            override suspend fun save(
                id: String,
                type: String,
                periodKey: String,
                statsJson: String,
                aiComment: String?,
                createdAtUtc: Long,
            ) = Unit

            override suspend fun byPeriod(
                type: String,
                periodKey: String,
            ) = null
        }

    private val noopNarrator =
        object : com.totop.app.domain.usecase.port.ReportNarrator {
            override suspend fun narrate(
                statsJson: String,
                requestId: String,
            ): com.totop.app.domain.usecase.port.ReportNarrator.NarrativeOutcome =
                com.totop.app.domain.usecase.port.ReportNarrator.NarrativeOutcome.Failure("not used")
        }
}
