package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 枚举与领域模型不变量单测：两档模式（含旧值兼容）、步骤状态、边类型、自评、非法值拒绝、UTC 时间戳。
 */
class DomainModelInvariantsTest {
    private val now = UtcTime.EPOCH_2024_01_01

    @Nested
    @DisplayName("枚举契约")
    inner class Enums {
        @Test
        fun `讲解模式为两档且默认不提前给答案`() {
            // 0.2.0 把原「提示引导」与「纯引导」合并：两者在真机上表现几乎一致，
            // 差别只剩「给不给最终解答」，而这件事 DIRECT/GUIDED 已经表达清楚了。
            assertEquals(listOf("DIRECT", "GUIDED"), TeachingMode.values().map { it.wire })
            assertEquals(TeachingMode.GUIDED, TeachingMode.DEFAULT)
            assertEquals(TeachingMode.DEFAULT, TeachingMode.fromWire("unknown"))
            assertEquals(null, TeachingMode.fromWireOrNull("unknown"))
        }

        @Test
        fun `已废弃的纯引导按提示引导读取_不丢失历史数据`() {
            // 旧版本会把 SOCRATIC 写进设置表与错题记录，读的时候必须有确定归宿，
            // 否则用户升级后要么模式重置、要么历史记录读不出来。
            assertEquals(TeachingMode.GUIDED, TeachingMode.fromWire("SOCRATIC"))
            assertEquals(TeachingMode.GUIDED, TeachingMode.fromWire("socratic"))
            assertEquals(TeachingMode.GUIDED, TeachingMode.fromWireOrNull("SOCRATIC"))
            assertTrue(TeachingMode.isLegacy("SOCratic"))
            assertFalse(TeachingMode.isLegacy("GUIDED"))
        }

        @Test
        fun `学生步骤状态对齐 JSON 契约取值`() {
            assertEquals(listOf("correct", "wrong", "missing"), StudentStepStatus.values().map { it.wire })
            assertEquals(StudentStepStatus.MISSING, StudentStepStatus.fromWire("nonsense"))
        }

        @Test
        fun `边类型对齐前置相关延伸三类`() {
            assertEquals(listOf("prerequisite", "related", "extends"), EdgeType.values().map { it.wire })
            assertEquals(EdgeType.PREREQUISITE, EdgeType.fromWire("PREREQUISITE"))
            assertEquals(null, EdgeType.fromWire("unknown"))
        }

        @Test
        fun `自评得分正负与文案`() {
            assertEquals(1.0, RecallSelfResult.GOT_IT.score, 0.0)
            assertEquals(-1.0, RecallSelfResult.STILL_LOST.score, 0.0)
            assertEquals("做对了", RecallSelfResult.GOT_IT.label)
            assertEquals("还是不会", RecallSelfResult.STILL_LOST.label)
        }

        @Test
        fun `AI 阶段与难度档位`() {
            assertEquals(listOf("A", "B", "C", "D"), AiStage.values().map { it.wire })
            assertEquals(3, ExerciseDifficulty.values().size)
        }
    }

    @Nested
    @DisplayName("模型不变量")
    inner class Invariants {
        @Test
        fun `掌握度超出百分制时拒绝构造`() {
            assertThrows(IllegalArgumentException::class.java) { Mastery("KP_MATH_0001", 101.0, emptyList(), now) }
            assertThrows(IllegalArgumentException::class.java) { Mastery("KP_MATH_0001", -1.0, emptyList(), now) }
            assertThrows(IllegalArgumentException::class.java) { MasterySnapshot(0L, 120.0) }
        }

        @Test
        fun `提示级别必须在一到三之间`() {
            assertThrows(IllegalArgumentException::class.java) { HintLog("h", "m", 0, now) }
            assertThrows(IllegalArgumentException::class.java) { HintLog("h", "m", 4, now) }
            assertEquals(3, HintLog("h", "m", 3, now).level)
        }

        @Test
        fun `知识点置信度必须在零到一之间`() {
            assertThrows(IllegalArgumentException::class.java) {
                KpointAssignment("KP_MATH_0001", "n", 1.5, "u")
            }
        }

        @Test
        fun `标准解答步骤连续性判定`() {
            val ok =
                StandardSolution(
                    steps = listOf(SolutionStep(1, "a", "b"), SolutionStep(2, "c", "d")),
                    finalAnswer = "x",
                )
            val broken = StandardSolution(steps = listOf(SolutionStep(1, "a", "b"), SolutionStep(3, "c", "d")))
            assertTrue(ok.hasContiguousStepIndex())
            assertFalse(broken.hasContiguousStepIndex())
            assertEquals("c", ok.stepAt(2)?.content)
        }

        @Test
        fun `错题复发判定`() {
            val first =
                MistakeRecord(
                    id = "m1",
                    questionId = "q1",
                    errorType = ErrorType.CALCULATION_ERROR,
                    createdAtUtc = now,
                    recurrenceIndex = 1,
                )
            val second = first.copy(id = "m2", recurrenceIndex = 2)
            assertFalse(first.isRecurrence)
            assertTrue(second.isRecurrence)
            assertFalse(first.hasLocatedError)
            assertTrue(first.copy(firstErrorStepIdx = 2).hasLocatedError)
        }

        @Test
        fun `每日统计拒绝负值`() {
            assertThrows(IllegalArgumentException::class.java) {
                DailyStats(dateEpochDayUtc = 0L, subject = "math", mistakeCount = -1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                DailyStats(dateEpochDayUtc = 0L, subject = "math", studySeconds = -1L)
            }
        }

        @Test
        fun `AI 追踪汇总 token`() {
            val trace =
                AiTrace(
                    id = "t1",
                    requestId = "r1",
                    stage = AiStage.C,
                    model = "qwen",
                    promptVer = "v1",
                    inputTokens = 100,
                    outputTokens = 50,
                    latencyMs = 1200L,
                    createdAtUtc = now,
                )
            assertEquals(150, trace.totalTokens)
        }

        @Test
        fun `练习题受控知识点标记`() {
            val ok = Exercise("e1", "KP_MATH_0001", "stem", "answer", ExerciseDifficulty.MEDIUM, true, now)
            val bad = ok.copy(kpointId = "导数")
            assertTrue(ok.hasControlledKpoint)
            assertFalse(bad.hasControlledKpoint)
        }

        @Test
        fun `题目含图与选择题判定`() {
            val question =
                Question(
                    id = "q",
                    subject = "math",
                    grade = "高一",
                    stemText = "题干",
                    options = listOf("A", "B"),
                    hasFigure = true,
                    createdAtUtc = now,
                )
            assertTrue(question.isMultipleChoice)
            assertTrue(question.isVisualQuestion)
        }

        @Test
        fun `知识点默认前置查询只取前置边`() {
            val catalog =
                FakeKnowledgeCatalog(
                    listOf(
                        KnowledgePoint("KP_MATH_0001", "a", "math"),
                        KnowledgePoint("KP_MATH_0002", "b", "math"),
                        KnowledgePoint("KP_MATH_0003", "c", "math"),
                    ),
                    listOf(
                        KpointEdge("KP_MATH_0001", "KP_MATH_0002", EdgeType.PREREQUISITE),
                        KpointEdge("KP_MATH_0002", "KP_MATH_0003", EdgeType.RELATED),
                    ),
                )
            assertEquals(listOf("KP_MATH_0001"), catalog.prerequisiteIdsOf("KP_MATH_0002"))
            assertTrue(catalog.prerequisiteIdsOf("KP_MATH_0003").isEmpty())
            assertTrue(catalog.contains("KP_MATH_0001"))
            assertEquals(3, catalog.knowledgePointsOfSubject("math").size)
        }
    }
}
