@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.totop.app.data.local.mapper

import com.totop.app.data.local.entity.AiTraceEntity
import com.totop.app.data.local.entity.DailyStatsEntity
import com.totop.app.data.local.entity.ExerciseEntity
import com.totop.app.data.local.entity.HintLogEntity
import com.totop.app.data.local.entity.KnowledgePointEntity
import com.totop.app.data.local.entity.KpointEdgeEntity
import com.totop.app.data.local.entity.MasteryEntity
import com.totop.app.data.local.entity.MistakeRecordEntity
import com.totop.app.data.local.entity.QuestionEntity
import com.totop.app.data.local.entity.ReviewLogEntity
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiTrace
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.EdgeType
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.MistakeStatus
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.model.TeachingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 实体与领域模型之间的映射。
 *
 * 映射原则（与 domain 层 t2 的约定对齐）：
 * 1. 枚举一律按 domain 提供的 wire 值持久化，读取时用 domain 的 `fromWire` 解析，
 *    不在这里另造一套字符串常量；
 * 2. 读取侧对无法识别的枚举值一律回退到 domain 定义的兜底值，绝不抛异常，
 *    保证「一行脏数据不让整页崩」；
 * 3. 写入侧不做静默裁剪，行为由领域模型自身的构造函数保证。
 *
 * 本文件是纯 Kotlin（只依赖 kotlinx.serialization 与 domain），可在 JVM 单测直接覆盖。
 */
object EntityMappers {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

    private val stringListCodec = json

    // ---------- question ----------

    fun Question.toEntity(): QuestionEntity =
        QuestionEntity(
            id = id,
            subject = subject,
            grade = grade,
            stemText = stemText,
            knownJson = stringListCodec.encodeToString(known),
            ask = ask,
            optionsJson = stringListCodec.encodeToString(options),
            hasFigure = hasFigure,
            imagePath = imagePath,
            imageSha256 = imageSha256,
            sourceType = sourceType.wire,
            createdAt = createdAtUtc,
            selfMasteredAt = selfMasteredAtUtc,
            imagePathsJson = stringListCodec.encodeToString(imagePaths),
        )

    fun QuestionEntity.toDomain(): Question =
        Question(
            id = id,
            subject = subject,
            grade = grade,
            stemText = stemText,
            known = decodeStringList(knownJson),
            ask = ask,
            options = decodeStringList(optionsJson),
            hasFigure = hasFigure,
            imagePath = imagePath,
            imageSha256 = imageSha256,
            sourceType = QuestionSourceType.fromWire(sourceType),
            createdAtUtc = createdAt,
            selfMasteredAtUtc = selfMasteredAt,
            imagePaths = decodeStringList(imagePathsJson),
        )

    // ---------- mistake_record ----------

    fun MistakeRecord.toEntity(): MistakeRecordEntity =
        MistakeRecordEntity(
            id = id,
            questionId = questionId,
            studentStepsJson =
                stringListCodec.encodeToString(
                    studentSteps.map { PersistStudentStep(it.idx, it.content, it.status.wire) },
                ),
            firstErrorStepIdx = firstErrorStepIdx,
            errorType = errorType.code,
            errorExplain = errorExplain,
            correctPathJson =
                stringListCodec.encodeToString(
                    PersistSolution(
                        steps = correctSolution.steps.map { PersistSolutionStep(it.idx, it.content, it.why) },
                        finalAnswer = correctSolution.finalAnswer,
                        method = correctSolution.method,
                    ),
                ),
            finalAnswer = finalAnswer,
            mode = mode.wire,
            createdAt = createdAtUtc,
            recurrenceIndex = recurrenceIndex,
            status = status.wire,
            source = source,
        )

    fun MistakeRecordEntity.toDomain(): MistakeRecord =
        MistakeRecord(
            id = id,
            questionId = questionId,
            studentSteps = decodeStudentSteps(studentStepsJson),
            firstErrorStepIdx = firstErrorStepIdx,
            errorType = ErrorType.ofCode(errorType) ?: ErrorType.FALLBACK,
            errorExplain = errorExplain,
            correctSolution = decodeSolution(correctPathJson),
            finalAnswer = finalAnswer,
            mode = TeachingMode.fromWire(mode),
            createdAtUtc = createdAt,
            recurrenceIndex = recurrenceIndex.coerceAtLeast(1),
            status = MistakeStatus.fromWireOrWrong(status),
            source = source.ifBlank { "solve" },
        )

    // ---------- knowledge_point / kpoint_edge ----------

    fun KnowledgePoint.toEntity(): KnowledgePointEntity =
        KnowledgePointEntity(
            id = id,
            name = name,
            subject = subject,
            parentId = parentId,
            chapter = chapter,
            orderIndex = orderIndex,
            definition = definition,
            commonApproach = commonApproach,
            pitfallJson = stringListCodec.encodeToString(pitfalls),
        )

    fun KnowledgePointEntity.toDomain(): KnowledgePoint =
        KnowledgePoint(
            id = id,
            name = name,
            subject = subject,
            parentId = parentId,
            chapter = chapter,
            orderIndex = orderIndex,
            definition = definition,
            commonApproach = commonApproach,
            pitfalls = decodeStringList(pitfallJson),
        )

    fun KpointEdge.toEntity(): KpointEdgeEntity =
        KpointEdgeEntity(
            fromId = fromId,
            toId = toId,
            type = type.wire,
            weight = weight,
        )

    /** 边类型无法识别时返回 null：由调用方过滤，避免把未知边当成前置边污染学习路径。 */
    fun KpointEdgeEntity.toDomainOrNull(): KpointEdge? {
        val edgeType = EdgeType.fromWire(type) ?: return null
        return KpointEdge(fromId = fromId, toId = toId, type = edgeType, weight = weight)
    }

    fun KpointEdgeEntity.toDomain(): KpointEdge =
        toDomainOrNull() ?: KpointEdge(fromId = fromId, toId = toId, type = EdgeType.PREREQUISITE, weight = weight)

    // ---------- review_log / hint_log ----------

    fun ReviewLog.toEntity(): ReviewLogEntity =
        ReviewLogEntity(
            id = id,
            mistakeId = mistakeId,
            reviewedAt = reviewedAtUtc,
            selfResult = selfResult.wire,
        )

    /** 自评结果无法识别时回退为「还是不会」，避免把无效记录算成「会」而虚抬掌握度。 */
    fun ReviewLogEntity.toDomain(): ReviewLog =
        ReviewLog(
            id = id,
            mistakeId = mistakeId,
            reviewedAtUtc = reviewedAt,
            selfResult = RecallSelfResult.fromWire(selfResult) ?: RecallSelfResult.STILL_LOST,
        )

    fun HintLog.toEntity(): HintLogEntity =
        HintLogEntity(
            id = id,
            mistakeId = mistakeId,
            level = level,
            createdAt = createdAtUtc,
        )

    /** 级别越界说明数据被外部改写；按 1..3 夹取而不是抛异常，保证统计不会因单行损坏中断。 */
    fun HintLogEntity.toDomain(): HintLog =
        HintLog(
            id = id,
            mistakeId = mistakeId,
            level = level.coerceIn(MIN_HINT_LEVEL, MAX_HINT_LEVEL),
            createdAtUtc = createdAt,
        )

    // ---------- exercise ----------

    fun Exercise.toEntity(): ExerciseEntity =
        ExerciseEntity(
            id = id,
            kpointId = kpointId,
            stem = stem,
            answer = answer,
            difficulty = difficulty.wire,
            verified = verified,
            createdAt = createdAtUtc,
        )

    fun ExerciseEntity.toDomain(): Exercise =
        Exercise(
            id = id,
            kpointId = kpointId,
            stem = stem,
            answer = answer,
            difficulty = ExerciseDifficulty.fromWire(difficulty) ?: ExerciseDifficulty.MEDIUM,
            verified = verified,
            createdAtUtc = createdAt,
        )

    // ---------- mastery ----------

    fun Mastery.toEntity(): MasteryEntity =
        MasteryEntity(
            kpointId = kpointId,
            score = score,
            historyJson =
                stringListCodec.encodeToString(
                    history.map { PersistSnapshot(it.dateEpochDayUtc, it.score) },
                ),
            updatedAt = updatedAtUtc,
        )

    fun MasteryEntity.toDomain(): Mastery =
        Mastery(
            kpointId = kpointId,
            score = score.coerceIn(MIN_MASTERY, MAX_MASTERY),
            history = decodeHistory(historyJson),
            updatedAtUtc = updatedAt,
        )

    // ---------- daily_stats ----------

    fun DailyStats.toEntity(): DailyStatsEntity =
        DailyStatsEntity(
            date = dateEpochDayUtc,
            subject = subject,
            mistakeCount = mistakeCount,
            kpointCount = kpointCount,
            hintCount = hintCount,
            reviewDoneCount = reviewDoneCount,
            studySeconds = studySeconds,
        )

    fun DailyStatsEntity.toDomain(): DailyStats =
        DailyStats(
            dateEpochDayUtc = date,
            subject = subject,
            mistakeCount = mistakeCount.coerceAtLeast(0),
            kpointCount = kpointCount.coerceAtLeast(0),
            hintCount = hintCount.coerceAtLeast(0),
            reviewDoneCount = reviewDoneCount.coerceAtLeast(0),
            studySeconds = studySeconds.coerceAtLeast(0L),
        )

    // ---------- ai_trace ----------

    fun AiTrace.toEntity(): AiTraceEntity =
        AiTraceEntity(
            id = id,
            requestId = requestId,
            stage = stage.wire,
            model = model,
            promptVer = promptVer,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            ok = ok,
            errorMsg = errorMsg,
            createdAt = createdAtUtc,
        )

    fun AiTraceEntity.toDomain(): AiTrace =
        AiTrace(
            id = id,
            requestId = requestId,
            stage = AiStage.fromWire(stage) ?: AiStage.A,
            model = model,
            promptVer = promptVer,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            ok = ok,
            errorMsg = errorMsg,
            createdAtUtc = createdAt,
        )

    // ---------- JSON 解码工具 ----------

    internal fun decodeStringList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString<List<String>>(raw)
            }.getOrDefault(emptyList())
        }

    internal fun decodeStudentSteps(raw: String?): List<com.totop.app.domain.model.StudentStep> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed =
            runCatching {
                json.decodeFromString<List<PersistStudentStep>>(
                    raw,
                )
            }.getOrNull() ?: return emptyList()
        return parsed.map {
            com.totop.app.domain.model.StudentStep(
                idx = it.idx,
                content = it.content,
                status = com.totop.app.domain.model.StudentStepStatus.fromWire(it.status),
            )
        }
    }

    internal fun decodeSolution(raw: String?): com.totop.app.domain.model.StandardSolution {
        if (raw.isNullOrBlank()) return com.totop.app.domain.model.StandardSolution()
        val parsed =
            runCatching { json.decodeFromString<PersistSolution>(raw) }.getOrNull()
                ?: return com.totop.app.domain.model.StandardSolution()
        return com.totop.app.domain.model.StandardSolution(
            steps =
                parsed.steps.map {
                    com.totop.app.domain.model.SolutionStep(idx = it.idx, content = it.content, why = it.why)
                },
            finalAnswer = parsed.finalAnswer,
            method = parsed.method,
        )
    }

    internal fun decodeHistory(raw: String?): List<MasterySnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString<List<PersistSnapshot>>(raw) }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { runCatching { MasterySnapshot(it.day, it.score) }.getOrNull() }
    }

    private const val MIN_HINT_LEVEL = 1
    private const val MAX_HINT_LEVEL = 3
    private const val MIN_MASTERY = 0.0
    private const val MAX_MASTERY = 100.0
}

/** 顶层桥接：边实体的安全映射（无法识别的边类型返回 null）。 */
fun com.totop.app.data.local.entity.KpointEdgeEntity.toDomainEdgeOrNull(): KpointEdge? =
    EntityMappers.run { toDomainOrNull() }

// ---------- 持久化 DTO ----------

@kotlinx.serialization.Serializable
internal data class PersistStudentStep(val idx: Int, val content: String, val status: String)

@kotlinx.serialization.Serializable
internal data class PersistSolutionStep(val idx: Int, val content: String, val why: String)

@kotlinx.serialization.Serializable
internal data class PersistSolution(
    val steps: List<PersistSolutionStep> = emptyList(),
    val finalAnswer: String = "",
    val method: String = "",
)

@kotlinx.serialization.Serializable
internal data class PersistSnapshot(val day: Long, val score: Double)
