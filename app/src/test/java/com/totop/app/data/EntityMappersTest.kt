package com.totop.app.data

import com.totop.app.data.local.entity.QuestionEntity
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toDomain
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.data.local.mapper.toDomainEdgeOrNull
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiTrace
import com.totop.app.domain.model.DailyStats
import com.totop.app.domain.model.EdgeType
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.Exercise
import com.totop.app.domain.model.ExerciseDifficulty
import com.totop.app.domain.model.FirstError
import com.totop.app.domain.model.HintLog
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.KpointEdge
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasterySnapshot
import com.totop.app.domain.model.MistakeRecord
import com.totop.app.domain.model.Question
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.RecallSelfResult
import com.totop.app.domain.model.ReviewLog
import com.totop.app.domain.model.SolutionStep
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.model.TeachingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 实体 <-> 领域模型映射的 JVM 单测。
 *
 * 约定验证：枚举按 wire 值持久化；读取侧的脏数据一律回退，不抛异常。
 */
class EntityMappersTest {
    @Test
    @DisplayName("question 全字段往返")
    fun questionRoundTrip() {
        val question =
            Question(
                id = "q-1",
                subject = "math",
                grade = "高一",
                stemText = "已知 x^2 - 3x + 2 = 0，求 x。",
                known = listOf("x 为实数"),
                ask = "求 x",
                options = listOf("A. 1", "B. 2"),
                hasFigure = false,
                imagePath = "/data/user/0/com.totop.app/files/img/1.jpg",
                imageSha256 = "a".repeat(64),
                sourceType = QuestionSourceType.CAMERA,
                createdAtUtc = 1_700_000_000_000L,
            )
        val entity = question.toEntity()
        assertEquals("camera", entity.sourceType)
        assertEquals(1_700_000_000_000L, entity.createdAt)
        assertEquals(question, entity.toDomain())
    }

    @Test
    @DisplayName("question 空列表字段往返为空列表而不是 null")
    fun questionEmptyLists() {
        val question = Question(id = "q-2", subject = "math", grade = "初三", stemText = "s", createdAtUtc = 1L)
        val entity = question.toEntity()
        assertEquals("[]", entity.knownJson)
        assertEquals("[]", entity.optionsJson)
        assertEquals(question, entity.toDomain())
    }

    @Test
    @DisplayName("未知来源回退为默认来源，不抛异常")
    fun unknownSourceFallsBack() {
        val entity =
            QuestionEntity(
                id = "q",
                subject = "math",
                grade = "高",
                stemText = "s",
                knownJson = "[]",
                ask = "",
                optionsJson = "[]",
                hasFigure = false,
                imagePath = null,
                imageSha256 = null,
                sourceType = "bluetooth",
                createdAt = 1L,
            )
        assertEquals(QuestionSourceType.DEFAULT, entity.toDomain().sourceType)
    }

    @Test
    @DisplayName("mistake_record 全字段往返，含步骤与标准解答")
    fun mistakeRoundTrip() {
        val record =
            MistakeRecord(
                id = "m-1",
                questionId = "q-1",
                studentSteps =
                    listOf(
                        StudentStep(idx = 1, content = "移项", status = StudentStepStatus.WRONG),
                        StudentStep(idx = 2, content = "", status = StudentStepStatus.MISSING),
                    ),
                firstErrorStepIdx = 1,
                errorType = ErrorType.CALCULATION_ERROR,
                errorExplain = "移项未变号",
                correctSolution =
                    StandardSolution(
                        steps = listOf(SolutionStep(idx = 1, content = "移项变号", why = "等式性质")),
                        finalAnswer = "x = 1",
                        method = "移项法",
                    ),
                finalAnswer = "x = -1",
                mode = TeachingMode.GUIDED,
                createdAtUtc = 1_700_000_100_000L,
                recurrenceIndex = 3,
            )
        val entity = record.toEntity()
        assertEquals("CALCULATION_ERROR", entity.errorType)
        assertEquals("GUIDED", entity.mode)
        assertEquals(record, entity.toDomain())
    }

    @Test
    @DisplayName("未知错因回退为 FALLBACK 而不是抛异常")
    fun unknownErrorTypeFallsBack() {
        val record =
            MistakeRecord(
                id = "m",
                questionId = "q",
                errorType = ErrorType.CONCEPT_CONFUSION,
                createdAtUtc = 1L,
            )
        val entity = record.toEntity().copy(errorType = "SOMETHING_ELSE")
        assertEquals(ErrorType.FALLBACK, entity.toDomain().errorType)
    }

    @Test
    @DisplayName("未知讲解模式回退为默认模式")
    fun unknownModeFallsBack() {
        val entity =
            MistakeRecord(
                id = "m",
                questionId = "q",
                errorType = ErrorType.FORMULA_MISUSE,
                createdAtUtc = 1L,
            ).toEntity().copy(mode = "TURBO")
        assertEquals(TeachingMode.DEFAULT, entity.toDomain().mode)
    }

    @Test
    @DisplayName("recurrenceIndex 非法时夹取为 1")
    fun recurrenceClamped() {
        val entity =
            MistakeRecord(
                id = "m",
                questionId = "q",
                errorType = ErrorType.MISSING_STEPS,
                createdAtUtc = 1L,
            ).toEntity().copy(recurrenceIndex = 0)
        assertEquals(1, entity.toDomain().recurrenceIndex)
    }

    @Test
    @DisplayName("knowledge_point 与 kpoint_edge 往返")
    fun knowledgeRoundTrip() {
        val point =
            KnowledgePoint(
                id = "KP_MATH_0001",
                name = "有理数与实数",
                subject = "math",
                parentId = null,
                chapter = "有理数与实数",
                orderIndex = 1,
                definition = "定义",
                commonApproach = "思路",
                pitfalls = listOf("混合运算顺序错误"),
            )
        assertEquals(point, point.toEntity().toDomain())

        val edge =
            KpointEdge(fromId = "KP_MATH_0002", toId = "KP_MATH_0003", type = EdgeType.PREREQUISITE, weight = 1.0)
        assertEquals(edge, edge.toEntity().toDomain())

        val unknownType = edge.toEntity().copy(type = "depends_on")
        assertNull(unknownType.toDomainEdgeOrNull())
    }

    @Test
    @DisplayName("review/hint/exercise/mastery/dailyStats/aiTrace 往返")
    fun recordsRoundTrip() {
        val review = ReviewLog(id = "r", mistakeId = "m", reviewedAtUtc = 5L, selfResult = RecallSelfResult.GOT_IT)
        assertEquals(review, review.toEntity().toDomain())

        val unknownReview = review.toEntity().copy(selfResult = "maybe")
        assertEquals(RecallSelfResult.STILL_LOST, unknownReview.toDomain().selfResult)

        val hint = HintLog(id = "h", mistakeId = "m", level = 2, createdAtUtc = 6L)
        assertEquals(hint, hint.toEntity().toDomain())

        val badHint = hint.toEntity().copy(level = 9)
        assertEquals(3, badHint.toDomain().level)

        val exercise =
            Exercise(
                id = "e",
                kpointId = "KP_MATH_0001",
                stem = "s",
                answer = "a",
                difficulty = ExerciseDifficulty.HARD,
                verified = true,
                createdAtUtc = 7L,
            )
        assertEquals(exercise, exercise.toEntity().toDomain())

        val badExercise = exercise.toEntity().copy(difficulty = "extreme")
        assertEquals(ExerciseDifficulty.MEDIUM, badExercise.toDomain().difficulty)

        val mastery =
            Mastery(
                kpointId = "KP_MATH_0001",
                score = 63.5,
                history = listOf(MasterySnapshot(19_000L, 40.0), MasterySnapshot(19_001L, 63.5)),
                updatedAtUtc = 8L,
            )
        assertEquals(mastery, mastery.toEntity().toDomain())

        val stats =
            DailyStats(
                dateEpochDayUtc = 19_000L,
                subject = "math",
                mistakeCount = 3,
                kpointCount = 2,
                hintCount = 1,
                reviewDoneCount = 4,
                studySeconds = 1_200L,
            )
        assertEquals(stats, stats.toEntity().toDomain())

        val trace =
            AiTrace(
                id = "t",
                requestId = "req",
                stage = AiStage.C,
                model = "qwen-max",
                promptVer = "1.0.0",
                inputTokens = 100,
                outputTokens = 200,
                latencyMs = 3_000L,
                ok = false,
                errorMsg = "timeout",
                createdAtUtc = 9L,
            )
        assertEquals(trace, trace.toEntity().toDomain())

        val badTrace = trace.toEntity().copy(stage = "Z")
        assertEquals(AiStage.A, badTrace.toDomain().stage)
    }

    @Test
    @DisplayName("损坏 JSON 列被解码为空集合，不让一次查询失败")
    fun corruptedJsonColumnsDecodedSafely() {
        val entity =
            QuestionEntity(
                id = "q",
                subject = "math",
                grade = "g",
                stemText = "s",
                knownJson = "{",
                ask = "",
                optionsJson = "[1,2]",
                hasFigure = false,
                imagePath = null,
                imageSha256 = null,
                sourceType = "manual",
                createdAt = 1L,
            )
        val domain = entity.toDomain()
        assertTrue(domain.known.isEmpty())
        assertTrue(domain.options.isEmpty())
    }

    @Test
    @DisplayName("FirstError 与 KpointAssignment 通过 MistakeRecord 组合时保持受控 ID 语义")
    fun firstErrorCarriedByDomainOnly() {
        val firstError =
            FirstError(
                stepIdx = 1,
                type = ErrorType.READING_COMPREHENSION,
                explain = "漏看条件",
                correctThinking = "先圈条件",
            )
        // FirstError 由 mistake_record 的四列展开持久化
        val record =
            MistakeRecord(
                id = "m",
                questionId = "q",
                firstErrorStepIdx = firstError.stepIdx,
                errorType = firstError.type,
                errorExplain = firstError.explain,
                createdAtUtc = 1L,
            )
        val restored = record.toEntity().toDomain()
        assertEquals(1, restored.firstErrorStepIdx)
        assertEquals(ErrorType.READING_COMPREHENSION, restored.errorType)
        assertEquals("漏看条件", restored.errorExplain)
        assertEquals(EntityMappers.decodeStringList("[]"), emptyList<String>())
    }
}
