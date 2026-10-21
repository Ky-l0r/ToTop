package com.totop.app.domain.usecase

import com.totop.app.domain.model.AiFirstError
import com.totop.app.domain.model.AiStudentStep
import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.StandardSolution
import com.totop.app.domain.model.StudentStep
import com.totop.app.domain.model.StudentStepStatus
import com.totop.app.domain.usecase.port.SolveGatewayOutcome
import com.totop.app.domain.usecase.port.TokenUsageCount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * LocateErrorUseCase 单测：步骤切分、错因对齐、兜底与「不调用模型」的省 token 路径。
 */
class LocateErrorUseCaseTest {
    private val solution =
        StandardSolution(
            steps =
                listOf(
                    com.totop.app.domain.model.SolutionStep(1, "求导", "判断单调性"),
                    com.totop.app.domain.model.SolutionStep(2, "解不等式", "确定符号"),
                ),
            finalAnswer = "(1,+∞)",
            method = "导数法",
        )

    private fun useCase(gateway: FakeSolveGateway = FakeSolveGateway()) = LocateErrorUseCase(gateway = gateway)

    @Nested
    @DisplayName("步骤切分")
    inner class Splitting {
        @Test
        fun `按换行切分`() {
            val steps = useCase().splitRawText("求导得到 f'(x)=3x^2-3\n令其等于 0 求驻点\n判断符号")
            assertEquals(3, steps.size)
            assertEquals("求导得到 f'(x)=3x^2-3", steps[0])
        }

        @Test
        fun `按显式序号切分并去掉序号`() {
            val steps = useCase().splitRawText("1. 求导得 3x^2-3\n2. 解不等式得 x>1\n3. 写出区间")
            assertEquals(3, steps.size)
            assertFalse(steps[0].startsWith("1"))
            assertEquals("求导得 3x^2-3", steps[0])
        }

        @Test
        fun `按圈号切分`() {
            val steps = useCase().splitRawText("①先求导得到导函数\n②再解不等式")
            assertEquals(2, steps.size)
        }

        @Test
        fun `空输入返回空列表`() {
            assertTrue(useCase().splitRawText("   ").isEmpty())
            assertTrue(useCase().splitRawText("").isEmpty())
        }

        @Test
        fun `只有序号的残行被丢弃`() {
            val steps = useCase().splitRawText("1.\n2.")
            assertTrue(steps.isEmpty())
        }

        @Test
        fun `超长单步被截断`() {
            val long = "求导".repeat(500)
            val steps = useCase().splitRawText(long)
            assertTrue(steps.single().length <= LocateErrorUseCase.MAX_STEP_CHARS)
        }
    }

    @Nested
    @DisplayName("错因对齐")
    inner class Alignment {
        @Test
        fun `正常错因落地四要素`() {
            val outcome =
                useCase().align(
                    studentStepsFromModel =
                        listOf(
                            AiStudentStep(idx = 1, content = "先算 f(0)=0", status = "wrong"),
                            AiStudentStep(idx = 2, content = "直接写答案", status = "missing"),
                        ),
                    rawFirstError = AiFirstError(stepIdx = 1, type = "公式误用", explain = "没求导", correctThinking = "先求导"),
                )
            val error = outcome.firstError!!
            assertEquals(1, error.stepIdx)
            assertEquals(ErrorType.FORMULA_MISUSE, error.type)
            assertEquals("没求导", error.explain)
            assertEquals("先求导", error.correctThinking)
            assertEquals(1, outcome.firstErrorStepIdx)
            assertEquals(ErrorType.FORMULA_MISUSE, outcome.errorType)
            assertTrue(outcome.hasError)
        }

        @Test
        fun `空错因字符串落兜底并告警`() {
            val warnings = mutableListOf<String>()
            val outcome =
                useCase().align(
                    studentStepsFromModel = listOf(AiStudentStep(1, "做了点事", "wrong")),
                    rawFirstError = AiFirstError(stepIdx = 1, type = "粗心", explain = "", correctThinking = ""),
                    warnings = warnings,
                )
            assertEquals(ErrorType.FALLBACK, outcome.firstError?.type)
            assertTrue(warnings.any { it.contains("粗心") })
        }

        @Test
        fun `步骤序号越界时回退到第一个错误步骤`() {
            val warnings = mutableListOf<String>()
            val outcome =
                useCase().align(
                    studentStepsFromModel =
                        listOf(
                            AiStudentStep(1, "对的", "correct"),
                            AiStudentStep(2, "错的", "wrong"),
                        ),
                    rawFirstError = AiFirstError(stepIdx = 9, type = "计算失误", explain = "", correctThinking = ""),
                    warnings = warnings,
                )
            assertEquals(2, outcome.firstError?.stepIdx)
            assertTrue(warnings.any { it.contains("9") })
        }

        @Test
        fun `学生全对时不硬凑错因`() {
            val outcome =
                useCase().align(
                    studentStepsFromModel =
                        listOf(
                            AiStudentStep(1, "正确做法", "correct"),
                            AiStudentStep(2, "继续正确", "correct"),
                        ),
                    rawFirstError = null,
                )
            assertNull(outcome.firstError)
            assertFalse(outcome.hasError)
            assertNull(outcome.firstErrorStepIdx)
        }

        @Test
        fun `错因声明存在但学生步骤无一错误时忽略`() {
            val warnings = mutableListOf<String>()
            val outcome =
                useCase().align(
                    studentStepsFromModel = listOf(AiStudentStep(1, "正确", "correct")),
                    rawFirstError = AiFirstError(stepIdx = 1, type = "计算失误", explain = "", correctThinking = ""),
                    warnings = warnings,
                )
            assertEquals(1, outcome.firstError?.stepIdx, "声明的序号有效时应被采纳")
        }

        @Test
        fun `status 未知取值兜底为缺失`() {
            val outcome =
                useCase().align(
                    studentStepsFromModel = listOf(AiStudentStep(1, "某步骤", "unknown_status")),
                    rawFirstError = null,
                )
            assertEquals(StudentStepStatus.MISSING, outcome.studentSteps.single().status)
        }
    }

    @Nested
    @DisplayName("模型调用与省 token")
    inner class ModelCalls {
        @Test
        fun `学生过程为空时不调用模型`() =
            runTest {
                val gateway = FakeSolveGateway()
                val outcome =
                    useCase(gateway).locate(
                        LocateErrorRequest(
                            requestId = "r1",
                            stem = "题干",
                            studentRawText = "   ",
                            standardSolution = solution,
                        ),
                    )
                assertTrue(outcome is LocateErrorOutcome.NoStudentProcess)
                assertEquals(0, gateway.requests.size, "没有学生过程就不该花 token")
            }

        @Test
        fun `有学生过程时调用一次并透传错因`() =
            runTest {
                val gateway = FakeSolveGateway()
                val outcome =
                    useCase(gateway).locate(
                        LocateErrorRequest(
                            requestId = "r1",
                            stem = "题干",
                            studentRawText = "我先算 f(0)=0\n然后直接写答案",
                            standardSolution = solution,
                        ),
                    )
                assertTrue(outcome is LocateErrorOutcome.Located)
                assertEquals(1, gateway.requests.size)
                val located = outcome as LocateErrorOutcome.Located
                assertEquals(2, located.firstError?.stepIdx)
                assertEquals(ErrorType.FORMULA_MISUSE, located.errorType)
                assertEquals(TokenUsageCount(10, 20), located.usage)
            }

        @Test
        fun `网关失败时返回失败而不是空结果`() =
            runTest {
                val gateway =
                    FakeSolveGateway(
                        SolveGatewayOutcome.Failure(
                            stage = com.totop.app.domain.model.AiStage.C,
                            userMessage = "余额不足",
                        ),
                    )
                val outcome =
                    useCase(gateway).locate(
                        LocateErrorRequest("r1", "题干", studentRawText = "写了点东西", standardSolution = solution),
                    )
                assertTrue(outcome is LocateErrorOutcome.Failure)
                assertEquals("余额不足", (outcome as LocateErrorOutcome.Failure).userMessage)
            }
    }

    @Nested
    @DisplayName("步骤对照")
    inner class StepAlignmentTest {
        @Test
        fun `对照条数取两侧较大值`() {
            val studentSteps =
                listOf(
                    StudentStep(1, "a", StudentStepStatus.CORRECT),
                    StudentStep(2, "b", StudentStepStatus.WRONG),
                )
            val rows = useCase().alignment(solution, studentSteps, null)
            assertEquals(2, rows.size)
            assertEquals("求导", rows[0].standardStep?.content)
            assertEquals(StudentStepStatus.CORRECT, rows[0].studentStep?.status)
            assertFalse(rows.any { it.isFirstError })
        }

        @Test
        fun `标记首个错误行`() {
            val studentSteps = listOf(StudentStep(1, "a", StudentStepStatus.WRONG))
            val firstError = com.totop.app.domain.model.FirstError(1, ErrorType.CALCULATION_ERROR, "e", "t")
            val rows = useCase().alignment(solution, studentSteps, firstError)
            assertTrue(rows.first { it.position == 1 }.isFirstError)
        }

        @Test
        fun `两侧都为空时返回空列表`() {
            assertTrue(useCase().alignment(StandardSolution(), emptyList(), null).isEmpty())
        }
    }
}
