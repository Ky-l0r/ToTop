package com.totop.app.domain.usecase

import com.totop.app.domain.model.AiFirstError
import com.totop.app.domain.model.AiKnowledgePoint
import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiSolveResult
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.AiStudentStep
import com.totop.app.domain.model.AiSummaryCard
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointAssignment
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.usecase.port.DailyStatsStore
import com.totop.app.domain.usecase.port.DataExporter
import com.totop.app.domain.usecase.port.DraftExercise
import com.totop.app.domain.usecase.port.ExerciseDraftOutcome
import com.totop.app.domain.usecase.port.ExerciseGenerator
import com.totop.app.domain.usecase.port.ExerciseRequest
import com.totop.app.domain.usecase.port.ExerciseStore
import com.totop.app.domain.usecase.port.ExportArtifact
import com.totop.app.domain.usecase.port.ExportFormat
import com.totop.app.domain.usecase.port.ExportWriteResult
import com.totop.app.domain.usecase.port.HintStore
import com.totop.app.domain.usecase.port.ImageCompressOutcome
import com.totop.app.domain.usecase.port.ImageCompressor
import com.totop.app.domain.usecase.port.ImageSource
import com.totop.app.domain.usecase.port.KpointDirectory
import com.totop.app.domain.usecase.port.MasteryStore
import com.totop.app.domain.usecase.port.MistakeStore
import com.totop.app.domain.usecase.port.OcrOutcome
import com.totop.app.domain.usecase.port.OcrReader
import com.totop.app.domain.usecase.port.OcrReading
import com.totop.app.domain.usecase.port.PersistedSolve
import com.totop.app.domain.usecase.port.PromptVersionProvider
import com.totop.app.domain.usecase.port.QuestionStore
import com.totop.app.domain.usecase.port.ReportNarrator
import com.totop.app.domain.usecase.port.ReportStore
import com.totop.app.domain.usecase.port.RouteChoice
import com.totop.app.domain.usecase.port.RouteSelector
import com.totop.app.domain.usecase.port.RouteTarget
import com.totop.app.domain.usecase.port.SolveGateway
import com.totop.app.domain.usecase.port.SolveGatewayOutcome
import com.totop.app.domain.usecase.port.SolveGatewayRequest
import com.totop.app.domain.usecase.port.SolveImage
import com.totop.app.domain.usecase.port.SolveResultCache
import com.totop.app.domain.usecase.port.SolvedDraft
import com.totop.app.domain.usecase.port.SolvedRecordWriter
import com.totop.app.domain.usecase.port.TokenUsageCount
import com.totop.app.domain.usecase.port.VerificationOutcome
import java.util.concurrent.atomic.AtomicInteger

/*
 * 用例层单测的假实现集合。
 *
 * 全部是纯内存、无 IO、无 Android 的实现：这样「模式裁剪」「失败不入库」这类编排行为
 * 可以被精确断言，而不需要真实数据库或网络。
 *
 * 说明：此处使用普通块注释而非 KDoc，因为 ktlint 的 standard:no-consecutive-comments
 * 禁止两个 KDoc 相邻，而本文件紧随其后就是第一个类的 KDoc。
 */

/** 记录落库调用的假写入器（同时校验「失败时一次都没被调用」）。 */
class FakeSolvedWriter : SolvedRecordWriter {
    val drafts = mutableListOf<SolvedDraft>()
    var failure: Throwable? = null
    private val counter = AtomicInteger(0)

    /** 落库次数：断言「失败不入库」时直接看它是否为 0。 */
    val persistCount: Int get() = drafts.size

    override suspend fun persistAtomically(draft: SolvedDraft): PersistedSolve {
        failure?.let { throw it }
        drafts += draft
        val index = counter.incrementAndGet()
        val question = draft.question.copy(id = "q-" + index)
        val mistake = draft.mistake?.copy(id = "m-" + index, questionId = question.id)
        return PersistedSolve(
            question = question,
            mistake = mistake,
            questionCreatedNew = true,
        )
    }
}

/** 假图片压缩器。 */
class FakeImageCompressor(
    var outcome: ImageCompressOutcome =
        ImageCompressOutcome.Success(
            SolveImage(base64 = "AAA", sha256 = "a".repeat(64), width = 1280, height = 720, localPath = "/tmp/a.jpg"),
        ),
) : ImageCompressor {
    var calls: Int = 0

    override suspend fun compress(source: ImageSource): ImageCompressOutcome {
        calls++
        return outcome
    }
}

/** 假 OCR。 */
class FakeOcrReader(
    var outcome: OcrOutcome =
        OcrOutcome.Success(
            OcrReading(text = "求 f(x)=x^3-3x 的单调区间", avgConfidence = 0.95, hasFigure = false),
        ),
) : OcrReader {
    var calls: Int = 0

    override suspend fun read(image: SolveImage): OcrOutcome {
        calls++
        return outcome
    }
}

/** 假路由：固定返回文本档（便于断言不触发视觉开销）。 */
class FakeRouter(
    var target: RouteTarget = RouteTarget.TEXT,
) : RouteSelector {
    var lastVisionAvailable: Boolean? = null

    override fun select(
        reading: OcrReading,
        visionAvailable: Boolean,
    ): RouteChoice {
        lastVisionAvailable = visionAvailable
        return RouteChoice(
            target = target,
            model = if (target == RouteTarget.VISION) "vision-model" else "text-model",
            attachImage = target == RouteTarget.VISION,
            reason = "测试固定路由",
        )
    }
}

/** 假网关：按预设返回成功或失败，并记录请求。 */
class FakeSolveGateway(
    var outcome: SolveGatewayOutcome = SolveGatewayOutcome.Success(contractResult(), TokenUsageCount(10, 20)),
) : SolveGateway {
    val requests = mutableListOf<SolveGatewayRequest>()

    override suspend fun solve(request: SolveGatewayRequest): SolveGatewayOutcome {
        requests += request
        return outcome
    }

    companion object {
        /** 一份完整的、可通过严格结构校验的 6.4 契约结果。 */
        fun contractResult(
            errorType: String = "公式误用",
            stepIdx: Int = 2,
            kpointId: String = "KP_MATH_0001",
            finalAnswer: String = "递增区间为 (1,+∞)",
        ): AiSolveResult =
            AiSolveResult(
                question =
                    AiQuestion(
                        subject = "math",
                        grade = "高一",
                        stem = "求 f(x)=x^3-3x 的单调区间",
                        known = listOf("f(x)=x^3-3x"),
                        ask = "求单调区间",
                    ),
                standardSolution =
                    AiStandardSolution(
                        steps =
                            listOf(
                                AiSolutionStep(idx = 1, content = "求导得 f'(x)=3x^2-3", why = "导数符号决定单调性"),
                                AiSolutionStep(idx = 2, content = "解不等式 f'(x)>0 得 x>1 或 x<-1", why = "正区间对应递增"),
                            ),
                        finalAnswer = finalAnswer,
                        method = "导数法",
                    ),
                studentSteps =
                    listOf(
                        AiStudentStep(idx = 1, content = "先算 f(0)=0", status = "wrong"),
                        AiStudentStep(idx = 2, content = "直接写答案", status = "missing"),
                    ),
                firstError =
                    AiFirstError(
                        stepIdx = stepIdx,
                        type = errorType,
                        explain = "没有先求导",
                        correctThinking = "本题应从求导切入，因为单调性由导数符号决定",
                    ),
                knowledgePoints =
                    listOf(
                        AiKnowledgePoint(id = kpointId, name = "导数与单调性", confidence = 0.92, usage = "判断增减区间"),
                    ),
                summaryCard = AiSummaryCard(title = "单调性三步走", bullets = listOf("求导", "解不等式", "写区间")),
                aiGenerated = true,
            )
    }
}

/** 假缓存。 */
class FakeSolveCache : SolveResultCache {
    val entries = mutableMapOf<String, String>()
    var gets: Int = 0
    var puts: Int = 0

    override suspend fun get(storageKey: String): String? {
        gets++
        return entries[storageKey]
    }

    override suspend fun put(
        storageKey: String,
        resultJson: String,
    ) {
        puts++
        entries[storageKey] = resultJson
    }
}

/** 假提示词版本。 */
class FakePromptVersion(var version: String = "1.0.0") : PromptVersionProvider {
    override suspend fun currentVersion(): String = version
}

/** 假知识点目录。 */
class FakeKpointDirectory(
    private val points: List<KnowledgePoint> = defaultPoints(),
    private val edgeList: List<KpointEdge> = emptyList(),
    var candidates: List<KnowledgePoint> = defaultPoints(),
) : KpointDirectory {
    override suspend fun allKnowledgePoints(): List<KnowledgePoint> = points

    override suspend fun knowledgePoint(id: String): KnowledgePoint? = points.firstOrNull { it.id == id }

    override suspend fun edges(): List<KpointEdge> = edgeList

    override suspend fun idByName(name: String): String? = points.firstOrNull { it.name == name }?.id

    override suspend fun candidatesFor(
        stemText: String,
        subject: String,
        limit: Int,
    ): List<KnowledgePoint> = candidates.take(limit)

    override suspend fun prerequisiteIdsOf(kpointId: String): List<String> =
        edgeList.filter { it.toId == kpointId && it.type == com.totop.app.domain.model.EdgeType.PREREQUISITE }
            .map { it.fromId }

    companion object {
        fun defaultPoints(): List<KnowledgePoint> =
            listOf(
                KnowledgePoint(id = "KP_MATH_0001", name = "导数与单调性", subject = "math"),
                KnowledgePoint(id = "KP_MATH_0002", name = "一元二次不等式", subject = "math"),
            )
    }
}

/** 假题目存储。 */
class FakeQuestionStore(private val questions: Map<String, Question> = emptyMap()) : QuestionStore {
    override suspend fun byId(id: String): Question? = questions[id]

    override suspend fun byImageSha256(sha256: String): Question? =
        questions.values.firstOrNull { it.imageSha256 == sha256 }

    override suspend fun saveDeduplicated(question: Question): QuestionStore.SavedQuestion =
        QuestionStore.SavedQuestion(question, createdNew = true)
}

/** 假错题存储。 */
class FakeMistakeStore(
    private var records: List<MistakeRecord> = emptyList(),
    private var kpointMap: Map<String, List<String>> = emptyMap(),
) : MistakeStore {
    val saved = mutableListOf<MistakeRecord>()
    val reviews = mutableListOf<ReviewLog>()

    override suspend fun byId(id: String): MistakeStore.MistakeWithKpoints? =
        records.firstOrNull { it.id == id }?.let {
            MistakeStore.MistakeWithKpoints(it, kpointMap[it.id].orEmpty())
        }

    override suspend fun byQuestionId(questionId: String): List<MistakeRecord> =
        records.filter { it.questionId == questionId }

    override suspend fun save(
        record: MistakeRecord,
        assignments: List<KpointAssignment>,
        recomputeRecurrence: Boolean,
    ): MistakeRecord {
        saved += record
        return record
    }

    override suspend fun recent(limit: Int): List<MistakeRecord> = records.take(limit)

    override suspend fun rangeOf(
        fromUtc: Long,
        toUtc: Long,
        limit: Int,
    ): List<MistakeRecord> = records.filter { it.createdAtUtc in fromUtc until toUtc }.take(limit)

    override suspend fun kpointsOfMistake(mistakeId: String): List<String> = kpointMap[mistakeId].orEmpty()

    override suspend fun mistakeIdsOfKpoint(kpointId: String): List<String> =
        kpointMap.filterValues { kpointId in it }.keys.toList()

    override suspend fun countCreatedBetween(
        fromUtc: Long,
        toUtc: Long,
    ): Int = records.count { it.createdAtUtc in fromUtc until toUtc }

    override suspend fun mistakeTimesOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): List<Long> {
        val ids = mistakeIdsOfKpoint(kpointId).toSet()
        return records.filter { it.id in ids && it.createdAtUtc in fromUtc until toUtc }
            .map { it.createdAtUtc }
            .sorted()
    }

    override suspend fun errorTypeDistribution(
        fromUtc: Long,
        toUtc: Long,
    ): Map<String, Int> =
        records.filter { it.createdAtUtc in fromUtc until toUtc }
            .groupBy { it.errorType.code }
            .mapValues { (_, list) -> list.size }

    override suspend fun kpointDistribution(
        fromUtc: Long,
        toUtc: Long,
    ): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        records.filter { it.createdAtUtc in fromUtc until toUtc }.forEach { record ->
            kpointMap[record.id].orEmpty().forEach { kpointId ->
                result[kpointId] = (result[kpointId] ?: 0) + 1
            }
        }
        return result
    }

    override suspend fun addReview(log: ReviewLog) {
        reviews += log
    }

    override suspend fun reviewsOf(mistakeId: String): List<ReviewLog> = reviews.filter { it.mistakeId == mistakeId }

    override suspend fun reviewsOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): List<ReviewLog> {
        val ids = mistakeIdsOfKpoint(kpointId).toSet()
        return reviews.filter { it.mistakeId in ids && it.reviewedAtUtc in fromUtc until toUtc }
    }

    override suspend fun reviewCountBetween(
        fromUtc: Long,
        toUtc: Long,
        selfResultWire: String,
    ): Int = 0
}

/** 假掌握度存储。 */
class FakeMasteryStore(private var items: List<Mastery> = emptyList()) : MasteryStore {
    val saved = mutableListOf<Mastery>()

    override suspend fun masteryOf(kpointId: String): Mastery? = items.firstOrNull { it.kpointId == kpointId }

    override suspend fun allMastery(): List<Mastery> = items

    override suspend fun save(mastery: Mastery): Mastery {
        saved += mastery
        items = items.filter { it.kpointId != mastery.kpointId } + mastery
        return mastery
    }

    override suspend fun saveAll(items: List<Mastery>): List<Mastery> {
        items.forEach { save(it) }
        return items
    }
}

/** 假日汇总存储。 */
class FakeDailyStatsStore(private var items: List<DailyStats> = emptyList()) : DailyStatsStore {
    var lastBetweenRange: Pair<Long, Long>? = null

    override suspend fun between(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): List<DailyStats> {
        lastBetweenRange = fromDateEpochDay to toDateEpochDay
        return items.filter { it.dateEpochDayUtc in fromDateEpochDay..toDateEpochDay }
    }

    override suspend fun sumMistakeCount(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Int = between(fromDateEpochDay, toDateEpochDay).sumOf { it.mistakeCount }

    override suspend fun sumReviewDoneCount(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Int = between(fromDateEpochDay, toDateEpochDay).sumOf { it.reviewDoneCount }

    override suspend fun sumStudySeconds(
        fromDateEpochDay: Long,
        toDateEpochDay: Long,
    ): Long = between(fromDateEpochDay, toDateEpochDay).sumOf { it.studySeconds }
}

/** 假提示存储。 */
class FakeHintStore(
    private var hintLogs: List<HintLog> = emptyList(),
    private val mistakeOwners: Map<String, String> = emptyMap(),
) : HintStore {
    override suspend fun hintsOf(mistakeId: String): List<HintLog> = hintLogs.filter { it.mistakeId == mistakeId }

    override suspend fun hintCountOfKpoint(
        kpointId: String,
        fromUtc: Long,
        toUtc: Long,
    ): Int =
        hintLogs.count {
            mistakeOwners[it.mistakeId] == kpointId && it.createdAtUtc in fromUtc until toUtc
        }
}

/** 假报告存储。 */
class FakeReportStore : ReportStore {
    val saved = mutableListOf<ReportStore.ReportRecord>()

    override suspend fun save(
        id: String,
        type: String,
        periodKey: String,
        statsJson: String,
        aiComment: String?,
        createdAtUtc: Long,
    ) {
        saved += ReportStore.ReportRecord(id, type, periodKey, statsJson, aiComment, createdAtUtc)
    }

    override suspend fun byPeriod(
        type: String,
        periodKey: String,
    ): ReportStore.ReportRecord? = saved.firstOrNull { it.type == type && it.periodKey == periodKey }
}

/** 假练习题存储。 */
class FakeExerciseStore : ExerciseStore {
    val saved = mutableListOf<Exercise>()

    override suspend fun verifiedOfKpoint(kpointId: String): List<Exercise> =
        saved.filter { it.kpointId == kpointId && it.verified }

    override suspend fun save(exercise: Exercise) {
        saved += exercise
    }

    override suspend fun saveAll(exercises: List<Exercise>) {
        saved += exercises
    }

    override suspend fun countVerified(): Int = saved.count { it.verified }
}

/** 可编程的假练习生成器。 */
class FakeExerciseGenerator(
    var drafts: ExerciseDraftOutcome =
        ExerciseDraftOutcome.Success(
            drafts = listOf(DraftExercise(stem = "求 f(x)=x^2-4x 的单调区间", answer = "在 (2,+∞) 递增")),
            usage = TokenUsageCount(100, 200),
        ),
    var verifications: MutableList<VerificationOutcome> =
        mutableListOf(
            VerificationOutcome.Success(
                consistent = true,
                derivedAnswer = "在 (2,+∞) 递增",
                usage = TokenUsageCount(50, 60),
            ),
        ),
) : ExerciseGenerator {
    val generated = mutableListOf<ExerciseRequest>()
    val verified = mutableListOf<String>()

    override suspend fun generate(
        request: ExerciseRequest,
        requestId: String,
    ): ExerciseDraftOutcome {
        generated += request
        return drafts
    }

    override suspend fun verify(
        stem: String,
        claimedAnswer: String,
        requestId: String,
    ): VerificationOutcome {
        verified += stem
        val next = if (verifications.isEmpty()) null else verifications.removeAt(0)
        return next ?: VerificationOutcome.Success(consistent = false, derivedAnswer = null)
    }
}

/** 假报告解读。 */
class FakeReportNarrator(
    var outcome: ReportNarrator.NarrativeOutcome = ReportNarrator.NarrativeOutcome.Success("本周你在导数上有进步。"),
) : ReportNarrator {
    var calls: Int = 0

    override suspend fun narrate(
        statsJson: String,
        requestId: String,
    ): ReportNarrator.NarrativeOutcome {
        calls++
        return outcome
    }
}

/** 假导出器：只记录，不写盘。 */
class FakeDataExporter(
    private val formats: Set<ExportFormat> = ExportFormat.values().toSet(),
    var writeResult: ExportWriteResult = ExportWriteResult.Success("/tmp/export", 1024),
) : DataExporter {
    val written = mutableListOf<ExportArtifact>()

    override fun supportedFormats(): Set<ExportFormat> = formats

    override suspend fun write(
        artifact: ExportArtifact,
        destinationUri: String?,
    ): ExportWriteResult {
        written += artifact
        return writeResult
    }
}
