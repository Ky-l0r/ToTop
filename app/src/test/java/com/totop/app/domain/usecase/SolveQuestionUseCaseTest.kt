package com.totop.app.domain.usecase

import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiStandardSolution
import com.totop.app.domain.model.QuestionSourceType
import com.totop.app.domain.model.TeachingMode
import com.totop.app.domain.usecase.port.DomainClock
import com.totop.app.domain.usecase.port.DomainSettings
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.ImageCompressOutcome
import com.totop.app.domain.usecase.port.ImageSource
import com.totop.app.domain.usecase.port.ModelProfile
import com.totop.app.domain.usecase.port.RouteTarget
import com.totop.app.domain.usecase.port.SolveGatewayOutcome
import com.totop.app.domain.usecase.port.TokenUsageCount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * SolveQuestionUseCase 编排单测。
 *
 * 重点断言三件事：
 * 1. 两档模式的裁剪：GUIDED 不返回 finalAnswer 且不给步骤，DIRECT 全量；
 * 2. 失败不入库：FakeSolvedWriter.persistCount 必须为 0；
 * 3. 缓存命中 0 token 且不再调用 OCR / 网关。
 */
class SolveQuestionUseCaseTest {
    private val now = 1_704_067_200_000L // 2024-01-01T00:00:00Z

    private fun useCase(
        gateway: FakeSolveGateway = FakeSolveGateway(),
        writer: FakeSolvedWriter = FakeSolvedWriter(),
        compressor: FakeImageCompressor = FakeImageCompressor(),
        ocr: FakeOcrReader = FakeOcrReader(),
        router: FakeRouter = FakeRouter(),
        cache: FakeSolveCache = FakeSolveCache(),
        settings: DomainSettings = DomainSettings.DEFAULTS,
        profile: ModelProfile = ModelProfile.PLACEHOLDER,
    ) = SolveQuestionUseCase(
        compressor = compressor,
        ocr = ocr,
        router = router,
        gateway = gateway,
        cache = cache,
        cacheKeyProvider = FakePromptVersion(),
        kpoints = FakeKpointDirectory(),
        writer = writer,
        settings = settings,
        modelProfile = profile,
        clock = DomainClock.fixed(now),
        ids = IdGenerator.sequence("req"),
    )

    private fun request(mode: TeachingMode = TeachingMode.DIRECT) =
        SolveQuestionRequest(
            image = ImageSource.FilePath("/tmp/a.jpg"),
            manualSubject = "math",
            manualGrade = "高一",
            sourceType = QuestionSourceType.CAMERA,
            studentRawText = "我先算 f(0)=0，然后直接写答案",
            mode = mode,
        )

    @Nested
    @DisplayName("模式裁剪")
    inner class ModeTrimming {
        @Test
        fun `DIRECT 下发完整步骤与答案`() =
            runTest {
                val writer = FakeSolvedWriter()
                val outcome = useCase(writer = writer).solve(request(TeachingMode.DIRECT))
                assertTrue(outcome is SolveOutcome.Success, "实际为 " + outcome)
                val success = outcome as SolveOutcome.Success
                assertEquals(2, success.view.solutionSteps.size)
                assertEquals("递增区间为 (1,+∞)", success.view.finalAnswer)
                assertFalse(success.view.answerWithheld)
                assertTrue(success.view.hints.isEmpty())
                assertTrue(success.view.guidance.isEmpty())
                assertTrue(success.view.hasFullSolution)
                assertEquals(1, writer.persistCount)
            }

        @Test
        fun `GUIDED 不返回 finalAnswer 与步骤_只给提示与引导提问`() =
            runTest {
                val writer = FakeSolvedWriter()
                val outcome = useCase(writer = writer).solve(request(TeachingMode.GUIDED))
                assertTrue(outcome is SolveOutcome.Success)
                val view = (outcome as SolveOutcome.Success).view
                assertNull(view.finalAnswer, "GUIDED 绝不能下发答案")
                assertTrue(view.solutionSteps.isEmpty(), "GUIDED 不下发完整步骤")
                assertTrue(view.answerWithheld)
                assertEquals(3, view.hints.size)
                assertEquals(listOf(1, 2, 3), view.hints.map { it.level })
                assertTrue(view.hints.none { it.hint.contains("递增区间为") }, "提示中不得出现最终答案")
                // 两档合并后 GUIDED 也带引导提问（原先只有 SOCRATIC 有）
                assertTrue(view.guidance.isNotEmpty(), "引导模式应给出可回答的引导提问")
                assertTrue(view.guidance.none { it.contains("递增区间为") }, "引导中不得出现答案")
                assertEquals(1, writer.persistCount)
                assertEquals("递增区间为 (1,+∞)", writer.drafts.single().mistake?.finalAnswer)
            }

        @Test
        fun `引导模式同时给出提示与引导提问_但都不含答案`() =
            runTest {
                val outcome = useCase().solve(request(TeachingMode.GUIDED))
                assertTrue(outcome is SolveOutcome.Success)
                val view = (outcome as SolveOutcome.Success).view
                assertNull(view.finalAnswer)
                assertTrue(view.solutionSteps.isEmpty())
                assertTrue(view.answerWithheld)
                // 合并两档后 GUIDED 既给可解锁的提示，也给引导性提问
                assertTrue(view.hints.isNotEmpty())
                assertTrue(view.guidance.size >= TeachingModeTrimmer.MIN_GUIDANCE_QUESTIONS)
                assertTrue(view.guidance.none { it.contains("递增区间为") }, "引导中不得出现答案")
                view.hints.forEach { hint ->
                    assertFalse(hint.hint.contains("递增区间为"), "提示中不得出现答案")
                }
                assertFalse(view.hasFullSolution)
            }

        @Test
        fun `每种模式下错因与知识点都正常下发`() =
            runTest {
                TeachingMode.values().forEach { mode ->
                    val outcome = useCase().solve(request(mode))
                    val view = (outcome as SolveOutcome.Success).view
                    assertEquals(mode, view.mode)
                    assertEquals("公式误用", view.firstError?.type?.label, "模式 " + mode + " 下错因不应丢失")
                    assertEquals(1, view.kpoints.size)
                    assertTrue(view.aiGenerated)
                }
            }
    }

    @Nested
    @DisplayName("失败不入库")
    inner class FailureDoesNotPersist {
        @Test
        fun `网关失败时一次都不写库`() =
            runTest {
                val writer = FakeSolvedWriter()
                val gateway =
                    FakeSolveGateway(
                        outcome =
                            SolveGatewayOutcome.Failure(
                                stage = AiStage.C,
                                userMessage = "API Key 无效或已过期",
                                retryable = false,
                            ),
                    )
                val outcome = useCase(gateway = gateway, writer = writer).solve(request())
                assertTrue(outcome is SolveOutcome.Failed)
                assertEquals("API Key 无效或已过期", (outcome as SolveOutcome.Failed).userMessage)
                assertEquals(0, writer.persistCount, "失败绝不能写库")
            }

        /**
         * 题干与标准解答缺失才是致命问题：这份结果对用户毫无价值。
         */
        @Test
        fun `标准解答结构缺失时拒绝入库`() =
            runTest {
                val writer = FakeSolvedWriter()
                val broken =
                    FakeSolveGateway.contractResult().copy(
                        standardSolution = AiStandardSolution(steps = emptyList()),
                    )
                val gateway = FakeSolveGateway(SolveGatewayOutcome.Success(broken, TokenUsageCount(1, 1)))
                val outcome = useCase(gateway = gateway, writer = writer).solve(request())
                assertTrue(outcome is SolveOutcome.Failed)
                assertTrue((outcome as SolveOutcome.Failed).userMessage.contains("standardSolution"))
                assertEquals(0, writer.persistCount)
            }

        /**
         * 学生没写解题过程时，阶段 C 按契约返回空 studentSteps —— 这是正确行为。
         *
         * 旧实现在这里判失败，导致「不填思路就永远解不出题」。
         * 现在只降级为告警：题照常解出来，只是没有错因。
         */
        @Test
        fun `没有学生步骤时仍正常返回标准解答`() =
            runTest {
                val writer = FakeSolvedWriter()
                val noStudentWork = FakeSolveGateway.contractResult().copy(studentSteps = emptyList())
                val gateway = FakeSolveGateway(SolveGatewayOutcome.Success(noStudentWork, TokenUsageCount(1, 1)))
                val outcome = useCase(gateway = gateway, writer = writer).solve(request())
                assertTrue(outcome is SolveOutcome.Success, "缺少学生步骤不应导致整次解题失败")
            }

        @Test
        fun `落库抛异常时返回失败且不产出部分成功`() =
            runTest {
                val writer = FakeSolvedWriter().apply { failure = IllegalStateException("disk full") }
                val outcome = useCase(writer = writer).solve(request())
                assertTrue(outcome is SolveOutcome.Failed)
                assertTrue((outcome as SolveOutcome.Failed).userMessage.contains("保存失败"))
            }

        @Test
        fun `图片压缩失败时不调用 OCR 与网关`() =
            runTest {
                val compressor =
                    FakeImageCompressor(
                        ImageCompressOutcome.Failure(userMessage = "图片文件不可读"),
                    )
                val ocr = FakeOcrReader()
                val gateway = FakeSolveGateway()
                val writer = FakeSolvedWriter()
                val outcome =
                    useCase(gateway = gateway, writer = writer, compressor = compressor, ocr = ocr)
                        .solve(request())
                assertTrue(outcome is SolveOutcome.ImageRejected)
                assertEquals(0, ocr.calls)
                assertEquals(0, gateway.requests.size)
                assertEquals(0, writer.persistCount)
            }

        @Test
        fun `未配置 Key 时进入本地模式且不写库`() =
            runTest {
                val writer = FakeSolvedWriter()
                val gateway = FakeSolveGateway()
                val outcome =
                    useCase(gateway = gateway, writer = writer, settings = DomainSettings.LOCAL_ONLY)
                        .solve(request())
                assertTrue(outcome is SolveOutcome.LlmNotConfigured)
                assertEquals(0, gateway.requests.size)
                assertEquals(0, writer.persistCount)
            }
    }

    @Nested
    @DisplayName("缓存与链路顺序")
    inner class CacheAndOrder {
        @Test
        fun `首次调用写缓存_第二次命中缓存且 0 token`() =
            runTest {
                val cache = FakeSolveCache()
                val gateway = FakeSolveGateway()
                val ocr = FakeOcrReader()
                val writer = FakeSolvedWriter()
                val target = useCase(gateway = gateway, writer = writer, ocr = ocr, cache = cache)

                val first = target.solve(request()) as SolveOutcome.Success
                assertFalse(first.cacheHit)
                assertEquals(1, cache.puts)
                assertEquals(1, gateway.requests.size)
                val ocrCallsAfterFirst = ocr.calls

                val second = target.solve(request()) as SolveOutcome.Success
                assertTrue(second.cacheHit, "第二次应命中缓存")
                assertEquals(0, second.usage.totalTokens, "命中缓存应为 0 token")
                assertEquals(1, gateway.requests.size, "命中缓存不得再调用网关")
                assertEquals(ocrCallsAfterFirst, ocr.calls, "命中缓存不得再调用 OCR")
                assertEquals(2, second.view.solutionSteps.size, "缓存命中仍要正确裁剪")
            }

        @Test
        fun `缓存键包含 mode_切换模式不会串用`() =
            runTest {
                val cache = FakeSolveCache()
                val gateway = FakeSolveGateway()
                val target = useCase(gateway = gateway, cache = cache)
                target.solve(request(TeachingMode.DIRECT))
                target.solve(request(TeachingMode.GUIDED))
                assertEquals(2, gateway.requests.size, "不同模式必须各自调用，不能复用 DIRECT 结果")
                assertEquals(2, cache.puts)
                assertTrue(cache.entries.keys.any { it.contains(TeachingMode.DIRECT.wire) })
                assertTrue(cache.entries.keys.any { it.contains(TeachingMode.GUIDED.wire) })
            }

        @Test
        fun `缓存内容损坏时当作未命中继续真实链路`() =
            runTest {
                val cache = FakeSolveCache()
                val gateway = FakeSolveGateway()
                val target = useCase(gateway = gateway, cache = cache)
                val key =
                    SolveCacheKey("a".repeat(64), "1.0.0", ModelProfile.PLACEHOLDER.profileId, TeachingMode.DIRECT)
                cache.entries[key.storageKey] = "{not json"
                val outcome = target.solve(request())
                assertTrue(outcome is SolveOutcome.Success)
                assertEquals(1, gateway.requests.size, "坏缓存不应阻塞真实链路")
            }

        @Test
        fun `压缩与 OCR 各调用一次`() =
            runTest {
                val compressor = FakeImageCompressor()
                val ocr = FakeOcrReader()
                useCase(compressor = compressor, ocr = ocr).solve(request())
                assertEquals(1, compressor.calls)
                assertEquals(1, ocr.calls)
            }

        @Test
        fun `纯手输题干不调用压缩与 OCR`() =
            runTest {
                val compressor = FakeImageCompressor()
                val ocr = FakeOcrReader()
                val writer = FakeSolvedWriter()
                val outcome =
                    useCase(compressor = compressor, ocr = ocr, writer = writer).solve(
                        SolveQuestionRequest(manualStem = "求 f(x)=x^2 的最小值", mode = TeachingMode.DIRECT),
                    )
                assertTrue(outcome is SolveOutcome.Success)
                assertEquals(0, compressor.calls)
                assertEquals(0, ocr.calls)
                assertEquals(QuestionSourceType.MANUAL, (outcome as SolveOutcome.Success).question.sourceType)
            }
    }

    @Nested
    @DisplayName("响应内容与下游字段")
    inner class Content {
        @Test
        fun `错题记录落在错题本_题目与知识点一并落库`() =
            runTest {
                val writer = FakeSolvedWriter()
                val outcome = useCase(writer = writer).solve(request()) as SolveOutcome.Success
                assertEquals(1, writer.persistCount)
                val draft = writer.drafts.single()
                assertNotNull(draft.mistake)
                assertEquals(2, draft.mistake?.firstErrorStepIdx)
                assertEquals(1, draft.assignments.size)
                assertEquals("KP_MATH_0001", draft.assignments.single().kpointId)
                assertNotNull(outcome.record)
                assertFalse(outcome.noErrorFound)
            }

        @Test
        fun `学生全对时不写错题本但题目仍入库`() =
            runTest {
                val writer = FakeSolvedWriter()
                val noError = FakeSolveGateway.contractResult().copy(firstError = null)
                val gateway = FakeSolveGateway(SolveGatewayOutcome.Success(noError, TokenUsageCount(1, 1)))
                val outcome = useCase(gateway = gateway, writer = writer).solve(request()) as SolveOutcome.Success
                assertNull(writer.drafts.single().mistake, "没有错误就不进错题本")
                assertNull(outcome.record)
                assertTrue(outcome.noErrorFound)
            }

        @Test
        fun `网关返回的告警全部透传`() =
            runTest {
                val gateway =
                    FakeSolveGateway(
                        SolveGatewayOutcome.Success(
                            FakeSolveGateway.contractResult(),
                            TokenUsageCount(1, 1),
                            warnings = listOf("错因未命中固定七类，已归入兜底：粗心"),
                        ),
                    )
                val outcome = useCase(gateway = gateway).solve(request()) as SolveOutcome.Success
                assertTrue(outcome.warnings.any { it.contains("粗心") })
            }

        @Test
        fun `诊断信息记录路由原因与 OCR 置信度`() =
            runTest {
                val target = useCase()
                target.solve(request())
                val diagnostics = target.lastDiagnostics
                assertNotNull(diagnostics)
                assertEquals("测试固定路由", diagnostics?.routeReason)
                assertEquals(0.95, diagnostics?.ocrConfidence ?: 0.0, 1e-9)
            }

        @Test
        fun `视觉档要求带图_无图时拒绝`() =
            runTest {
                val router = FakeRouter(RouteTarget.VISION)
                val outcome =
                    useCase(router = router).solve(
                        SolveQuestionRequest(manualStem = "求面积", mode = TeachingMode.DIRECT),
                    )
                assertTrue(outcome is SolveOutcome.ImageRejected)
            }
    }
}
