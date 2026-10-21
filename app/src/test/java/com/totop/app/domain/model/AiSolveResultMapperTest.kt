package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * AiSolveResultMapper 单测：DTO → 领域模型的安全映射，
 * 覆盖错因兜底、知识点受控 ID 过滤、步骤重编号与空数据边界。
 */
class AiSolveResultMapperTest {
    private val question =
        Question(
            id = "q1",
            subject = "math",
            grade = "高一",
            stemText = "求 f(x)=x^3-3x 的单调区间",
            createdAtUtc = UtcTime.EPOCH_2024_01_01,
        )

    @Nested
    @DisplayName("标准解答与步骤")
    inner class Steps {
        @Test
        fun `步骤序号按出现顺序重排`() {
            val dto =
                AiStandardSolution(
                    steps =
                        listOf(
                            AiSolutionStep(idx = 5, content = "求导", why = "判断单调性"),
                            AiSolutionStep(idx = 9, content = "解不等式", why = "确定符号"),
                        ),
                    finalAnswer = "(-∞,-1) ∪ (1,+∞)",
                    method = "导数法",
                )
            val solution = AiSolveResultMapper.mapStandardSolution(dto)
            assertEquals(listOf(1, 2), solution.steps.map { it.idx })
            assertTrue(solution.hasContiguousStepIndex())
            assertEquals("导数法", solution.method)
        }

        @Test
        fun `空步骤条目被丢弃`() {
            val dto =
                AiStandardSolution(
                    steps =
                        listOf(
                            AiSolutionStep(idx = 1, content = " ", why = null),
                            AiSolutionStep(idx = 2, content = "求导", why = null),
                        ),
                )
            val solution = AiSolveResultMapper.mapStandardSolution(dto)
            assertEquals(1, solution.stepCount)
            assertEquals("求导", solution.steps.single().content)
        }

        @Test
        fun `null 标准解答返回空对象`() {
            val solution = AiSolveResultMapper.mapStandardSolution(null)
            assertEquals(0, solution.stepCount)
            assertEquals("", solution.finalAnswer)
            assertFalse(solution.hasContiguousStepIndex())
        }

        @Test
        fun `学生步骤 status 未知时兜底为缺失`() {
            val steps =
                AiSolveResultMapper.mapStudentSteps(
                    listOf(
                        AiStudentStep(idx = 1, content = "a", status = "correct"),
                        AiStudentStep(idx = 2, content = "b", status = "unknown"),
                        AiStudentStep(idx = 3, content = "c", status = null),
                    ),
                )
            assertEquals(StudentStepStatus.CORRECT, steps[0].status)
            assertEquals(StudentStepStatus.FALLBACK, steps[1].status)
            assertEquals(StudentStepStatus.MISSING, steps[2].status)
            assertEquals(StudentStepStatus.MISSING, StudentStepStatus.fromWire("whatever"))
            assertNull(StudentStepStatus.fromWireOrNull("whatever"))
        }

        @Test
        fun `学生步骤缺 idx 时按顺序补齐`() {
            val steps =
                AiSolveResultMapper.mapStudentSteps(
                    listOf(AiStudentStep(content = "a"), AiStudentStep(content = "b")),
                )
            assertEquals(listOf(1, 2), steps.map { it.idx })
        }
    }

    @Nested
    @DisplayName("错因与知识点")
    inner class ErrorAndKpoints {
        @Test
        fun `中文错因映射为固定枚举`() {
            val warnings = mutableListOf<String>()
            val error =
                AiSolveResultMapper.mapFirstError(
                    AiFirstError(stepIdx = 2, type = "计算失误", explain = "符号写错", correctThinking = "先定符号"),
                    listOf(
                        StudentStep(1, "a", StudentStepStatus.CORRECT),
                        StudentStep(2, "b", StudentStepStatus.WRONG),
                    ),
                    warnings,
                )!!
            assertEquals(ErrorType.CALCULATION_ERROR, error.type)
            assertEquals(2, error.stepIdx)
            assertTrue(warnings.isEmpty())
        }

        @Test
        fun `未知错因落兜底并产生告警`() {
            val warnings = mutableListOf<String>()
            val error =
                AiSolveResultMapper.mapFirstError(
                    AiFirstError(stepIdx = 1, type = "粗心", explain = "", correctThinking = ""),
                    listOf(StudentStep(1, "a", StudentStepStatus.WRONG)),
                    warnings,
                )!!
            assertEquals(ErrorType.FALLBACK, error.type)
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("粗心"))
        }

        @Test
        fun `非受控知识点 ID 被丢弃并告警`() {
            val warnings = mutableListOf<String>()
            val points =
                AiSolveResultMapper.mapKnowledgePoints(
                    listOf(
                        AiKnowledgePoint(id = "KP_MATH_0312", name = "导数与单调性", confidence = 0.92, usage = "判断增减"),
                        AiKnowledgePoint(id = "导数", name = "自造知识点", confidence = 0.8, usage = "u"),
                        AiKnowledgePoint(id = null, name = "缺 ID", confidence = 0.5, usage = "u"),
                        AiKnowledgePoint(id = "KP_MATH_0312", name = "重复", confidence = 0.4, usage = "u"),
                    ),
                    warnings,
                )
            assertEquals(1, points.size)
            assertEquals("KP_MATH_0312", points.single().kpointId)
            assertEquals(3, warnings.size)
        }

        @Test
        fun `置信度越界被裁剪`() {
            val points =
                AiSolveResultMapper.mapKnowledgePoints(
                    listOf(
                        AiKnowledgePoint(id = "KP_MATH_0001", name = "a", confidence = 5.0, usage = "u"),
                        AiKnowledgePoint(id = "KP_MATH_0002", name = "b", confidence = -1.0, usage = "u"),
                        AiKnowledgePoint(id = "KP_MATH_0003", name = "c", confidence = Double.NaN, usage = "u"),
                    ),
                )
            assertEquals(1.0, points[0].confidence, 0.0)
            assertEquals(0.0, points[1].confidence, 0.0)
            assertEquals(0.0, points[2].confidence, 0.0)
        }

        @Test
        fun `空摘要卡返回 null`() {
            assertNull(AiSolveResultMapper.mapSummary(null))
            assertNull(AiSolveResultMapper.mapSummary(AiSummaryCard(title = "  ", bullets = listOf("  "))))
        }
    }

    @Nested
    @DisplayName("整体映射")
    inner class Full {
        @Test
        fun `完整 DTO 映射为领域聚合`() {
            val dto =
                AiSolveResult(
                    question = AiQuestion(subject = "math", grade = "高一", stem = "x"),
                    standardSolution =
                        AiStandardSolution(
                            steps = listOf(AiSolutionStep(1, "求导", "判断单调性")),
                            finalAnswer = "a",
                            method = "导数法",
                        ),
                    studentSteps = listOf(AiStudentStep(1, "代入", "wrong")),
                    firstError = AiFirstError(1, "公式误用", "e", "t"),
                    knowledgePoints = listOf(AiKnowledgePoint("KP_MATH_0312", "导数与单调性", 0.92, "u")),
                    summaryCard = AiSummaryCard("t", listOf("b")),
                    aiGenerated = true,
                )
            val mapping = AiSolveResultMapper.map(dto, question)
            assertEquals(ErrorType.FORMULA_MISUSE, mapping.content.errorTypeOrNull)
            assertEquals(0, mapping.content.errorStepIndexInStudentSteps)
            assertEquals(1, mapping.content.kpointAssignments.size)
            assertEquals("q1", mapping.content.question.id)
            assertTrue(mapping.content.aiGenerated)
            assertFalse(mapping.hasWarnings)
        }

        @Test
        fun `空 DTO 映射不抛异常并给出告警`() {
            val mapping = AiSolveResultMapper.map(AiSolveResult(), question)
            assertEquals(null, mapping.content.firstError)
            assertTrue(mapping.content.kpointAssignments.isEmpty())
            assertTrue(mapping.hasWarnings)
            assertTrue(mapping.warnings.any { it.contains("standardSolution") })
            assertTrue(mapping.warnings.any { it.contains("studentSteps") })
        }

        @Test
        fun `错误步骤不在学生步骤中时产生告警`() {
            val dto =
                AiSolveResult(
                    studentSteps = listOf(AiStudentStep(1, "a", "wrong")),
                    firstError = AiFirstError(stepIdx = 7, type = "计算失误", explain = "e", correctThinking = "t"),
                )
            val mapping = AiSolveResultMapper.map(dto, question)
            assertTrue(mapping.warnings.any { it.contains("stepIdx=7") })
            assertNull(mapping.content.errorStepIndexInStudentSteps)
        }
    }
}
