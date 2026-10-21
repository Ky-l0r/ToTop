package com.totop.app.data.remote

import com.totop.app.data.prefs.LlmCredentials
import com.totop.app.data.prefs.ModelPool
import com.totop.app.domain.model.AiStage
import com.totop.app.ml.ModelRouter
import com.totop.app.util.InMemoryAiTraceStore
import com.totop.app.util.MutableUtcClock
import com.totop.app.util.TraceRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 可编程的假传输层：按阶段返回预设文本或错误，不发真实网络请求。 */
class FakeTransport : LlmTransport {
    /** 每次调用的 (model, 是否带图, 用户提示词)。 */
    val calls = mutableListOf<RecordedCall>()

    /** 顺序返回的脚本；耗尽后返回最后一个。 */
    val scripts = mutableListOf<(RecordedCall) -> Flow<LlmChunk>>()

    data class RecordedCall(
        val model: String,
        val hasImage: Boolean,
        val userPrompt: String,
        val systemPrompt: String,
        val requestId: String,
    )

    fun enqueueText(vararg texts: String) {
        texts.forEach { text -> scripts.add { flowOfChunks(text) } }
    }

    fun enqueueFailure(error: LlmError) {
        scripts.add { flow { throw LlmException(error) } }
    }

    override fun stream(
        credentials: LlmCredentials,
        config: ChatRequestConfig,
        requestId: String,
        registry: LlmCallRegistry?,
        allowRetry: Boolean,
    ): Flow<LlmChunk> {
        val userMessage = config.messages.last()
        val userText = userMessage.parts.filterIsInstance<ChatPart.Text>().joinToString("") { it.text }
        val call =
            RecordedCall(
                model = config.model,
                hasImage = userMessage.parts.any { it is ChatPart.Image },
                userPrompt = userText,
                systemPrompt = config.messages.first().parts.filterIsInstance<ChatPart.Text>().joinToString(""),
                requestId = requestId,
            )
        calls.add(call)
        val script = scripts.removeFirstOrNull() ?: scripts.lastOrNull()
        return script?.invoke(call) ?: flowOfChunks("{}")
    }

    private fun flowOfChunks(text: String): Flow<LlmChunk> =
        flow {
            emit(LlmChunk.Delta(text))
            emit(LlmChunk.Finished(TokenUsage(inputTokens = 10, outputTokens = 20), "stop"))
        }
}

/** 测试用提示词资源（真实文件由 assets 成员维护，此处只做结构等价的最小副本）。 */
object TestPrompts {
    val FILES: Map<String, String> =
        mapOf(
            "prompts/prompt_version.json" to
                """
                {"promptVersion":"1.0.0","stages":{"A":"stage_a_identify.md","B":"stage_b_solve.md",
                "C":"stage_c_locate_error.md","D":"stage_d_kpoints.md"}}
                """.trimIndent(),
            "prompts/stage_a_identify.md" to "# 阶段 A\n只做结构化识题。",
            "prompts/stage_b_solve.md" to "# 阶段 B\n只做独立求解，看不到学生过程。",
            "prompts/stage_c_locate_error.md" to "# 阶段 C\n做错因定位，错因限七类。",
            "prompts/stage_d_kpoints.md" to "# 阶段 D\n只能从候选清单选 ID。",
        )

    fun repository(): PromptRepository = PromptRepository(AssetReader.of(FILES))
}

/** AiGateway 四阶段编排测试（SSE 增量、解析失败重试、绝不入库、缓存命中、trace 落库）。 */
class AiGatewayTest {
    private val stageA =
        """
        {"subject":"math","grade":"高一","stem":"求 f(x)=x^2-2x+1 在 [0,3] 上的最小值",
         "known":["f(x)=x^2-2x+1"],"ask":"求最小值","options":[],"hasFigure":false,"warnings":[]}
        """.trimIndent().replace("\n", "")

    private val stageB =
        """
        {"steps":[{"idx":1,"content":"配方得 (x-1)^2","why":"二次函数顶点式"},
                  {"idx":2,"content":"最小值在 x=1 处为 0","why":"顶点处取最小值"}],
         "finalAnswer":"0","method":"配方法","check":"端点值 1 与 4 均大于 0","confidence":0.95}
        """.trimIndent().replace("\n", "")

    private val stageC =
        """
        {"studentSteps":[{"idx":1,"content":"直接代入 x=0","status":"wrong","alignTo":1}],
         "firstError":{"stepIdx":1,"type":"方法选择不当","explain":"没有配方","correctThinking":"先配方再看顶点"},
         "summaryCard":{"title":"配方法","bullets":["先配方","再看顶点","最后比较端点"]}}
        """.trimIndent().replace("\n", "")

    private val stageD =
        """
        {"kpoints":[{"id":"KP_MATH_0001","name":"二次函数","confidence":0.9,"usage":"求最值"}],
         "primaryId":"KP_MATH_0001","unmatched":false}
        """.trimIndent().replace("\n", "")

    private fun credentials() =
        LlmCredentials(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "qwen-plus",
            textModel = "qwen-plus",
            visionModel = "qwen-vl-plus",
        )

    private fun gateway(
        transport: FakeTransport,
        cache: LlmCache? = null,
        traceStore: InMemoryAiTraceStore = InMemoryAiTraceStore(),
        clock: MutableUtcClock = MutableUtcClock(1_700_000_000_000L),
    ): Triple<AiGateway, TraceRecorder, InMemoryAiTraceStore> {
        val recorder = TraceRecorder(traceStore, clock)
        val gateway =
            AiGateway(
                llmClient = transport,
                promptRepository = TestPrompts.repository(),
                traceRecorder = recorder,
                cache = cache,
                clock = clock,
            )
        return Triple(gateway, recorder, traceStore)
    }

    private fun request(
        ocrConfidence: Double = 0.95,
        hasFigure: Boolean = false,
        student: String = "我直接代入 x=0",
    ) = SolveRequest(
        ocr =
            com.totop.app.ml.OcrSnapshot(
                text = "求 f(x)=x^2-2x+1 在 [0,3] 上的最小值",
                avgConfidence = ocrConfidence,
                hasFigure = hasFigure,
            ),
        studentRawText = student,
        candidateKpoints = listOf(CandidateKpoint("KP_MATH_0001", "二次函数", "函数")),
        requestId = "req-1",
    )

    @Test
    @DisplayName("四阶段顺序编排：A -> B -> C -> D，且阶段 B 不注入学生过程")
    fun fourStagesInOrder() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText(stageA, stageB, stageC, stageD)
            val (gateway, _, _) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }

            assertEquals(4, transport.calls.size, "应恰好四次调用")
            val stages = events.filterIsInstance<SolveEvent.StageStarted>().map { it.stage }
            assertEquals(listOf(AiStage.A, AiStage.B, AiStage.C, AiStage.D), stages.distinct())

            assertFalse(transport.calls[1].userPrompt.contains("我直接代入"), "阶段 B 绝不能看到学生过程")
            assertTrue(transport.calls[2].userPrompt.contains("我直接代入"), "阶段 C 必须注入学生过程")
            assertTrue(transport.calls[3].userPrompt.contains("KP_MATH_0001"), "阶段 D 必须注入候选清单")

            assertTrue(events.last() is SolveEvent.Completed, "应成功完成")
            val completed = events.last() as SolveEvent.Completed
            assertEquals(1, completed.content.kpointAssignments.size)
            assertEquals(120, completed.usage.totalTokens, "四阶段各 30 token，累计 120")
        }

    @Test
    @DisplayName("高置信度纯印刷体走文本模型，低置信度走视觉模型并附图")
    fun routingAffectsTransport() =
        runTest {
            val cheapTransport = FakeTransport()
            cheapTransport.enqueueText(stageA, stageB, stageC, stageD)
            val (cheapGateway, _, _) = gateway(cheapTransport)
            cheapGateway.solve(
                credentials(),
                request(ocrConfidence = 0.95),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            )
                .collect { }
            assertEquals("qwen-plus", cheapTransport.calls.first().model)
            assertFalse(cheapTransport.calls.first().hasImage)

            val visionTransport = FakeTransport()
            visionTransport.enqueueText(stageA, stageB, stageC, stageD)
            val (visionGateway, _, _) = gateway(visionTransport)
            visionGateway.solve(
                credentials(),
                request(ocrConfidence = 0.42),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            )
                .collect { }
            assertEquals("qwen-vl-plus", visionTransport.calls.first().model)
        }

    @Test
    @DisplayName("阶段输出不可解析时用更严格提示词重试 1 次，成功则继续")
    fun strictRetryOnMalformedOutput() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText("这不是 JSON", stageA, stageB, stageC, stageD)
            val (gateway, _, traceStore) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }

            assertTrue(events.last() is SolveEvent.Completed, "重试后应成功")
            assertTrue(
                transport.calls[1].userPrompt.contains("上一次输出无法被解析"),
                "第二次尝试必须带更严格的提示词",
            )
            val failures = traceStore.byRequestId("req-1").count { !it.ok }
            assertTrue(failures >= 1, "失败的尝试也必须写 ai_trace")
        }

    @Test
    @DisplayName("两次都不可解析时整次失败，绝不返回残缺结果")
    fun doubleFailureAborts() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText("不是 JSON", "还不是 JSON")
            val (gateway, _, _) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }

            val failed = events.last()
            assertTrue(failed is SolveEvent.Failed, "应失败")
            assertTrue((failed as SolveEvent.Failed).error is LlmError.MalformedResponse)
            assertEquals(AiStage.A, failed.stage)
        }

    @Test
    @DisplayName("不可重试错误（Key 无效）立即失败，只调用一次")
    fun invalidKeyStopsImmediately() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueFailure(LlmError.InvalidKey())
            val (gateway, _, _) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }

            assertEquals(1, transport.calls.size)
            assertTrue((events.last() as SolveEvent.Failed).error is LlmError.InvalidKey)
        }

    @Test
    @DisplayName("缓存命中时阶段 A 不再调用模型（0 token）")
    fun cacheHitSkipsStageA() =
        runTest {
            val cache = LlmCache(InMemoryCacheStore(), now = { 1_700_000_000_000L })
            val transport = FakeTransport()
            // 两次完整流程共 7 次调用（第二次阶段 A 命中缓存）
            transport.enqueueText(stageA, stageB, stageC, stageD, stageB, stageC, stageD)
            val (gateway, _, _) = gateway(transport, cache = cache)

            gateway.solve(credentials(), request(), ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")))
                .collect { }
            assertEquals(4, transport.calls.size)

            // 第二次：阶段 A 命中缓存，只调 B/C/D
            gateway.solve(
                credentials(),
                request().copy(requestId = "req-2"),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { }
            assertEquals(7, transport.calls.size, "第二次应只调用 3 次")
            assertEquals(1L, cache.hitCount())
        }

    @Test
    @DisplayName("每次阶段调用都写 ai_trace，字段齐全")
    fun traceWrittenPerStage() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText(stageA, stageB, stageC, stageD)
            val (gateway, _, traceStore) = gateway(transport)

            gateway.solve(credentials(), request(), ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")))
                .collect { }

            val traces = traceStore.byRequestId("req-1")
            assertEquals(4, traces.size, "四阶段各一条")
            assertEquals(
                listOf(AiStage.A, AiStage.B, AiStage.C, AiStage.D),
                traces.map { it.stage },
            )
            traces.forEach { trace ->
                assertEquals("qwen-plus", trace.model)
                assertEquals("1.0.0", trace.promptVer)
                assertEquals(10, trace.inputTokens)
                assertEquals(20, trace.outputTokens)
                assertTrue(trace.ok, "成功调用 ok 必须为 true")
            }
        }

    @Test
    @DisplayName("同一 requestId 重复调用被幂等守卫拒绝")
    fun idempotencyGuardBlocksDuplicate() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText(stageA, stageB, stageC, stageD)
            val (gateway, _, _) = gateway(transport)

            gateway.solve(credentials(), request(), ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")))
                .collect { }
            val events = mutableListOf<SolveEvent>()
            gateway.solve(credentials(), request(), ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")))
                .collect { events.add(it) }

            assertEquals(4, transport.calls.size, "重复请求不得再次调用模型")
            assertTrue(events.last() is SolveEvent.Failed)
            assertTrue((events.last() as SolveEvent.Failed).error.detail.orEmpty().contains("重复请求"))
        }

    @Test
    @DisplayName("未提供学生过程时仍可完成，但必须产生告警")
    fun missingStudentTextProducesWarning() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText(stageA, stageB, stageC, stageD)
            val (gateway, _, _) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(student = ""),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }

            val completed = events.last() as SolveEvent.Completed
            assertTrue(
                completed.warnings.any { it.contains("未提供学生过程") || it.contains("无法定位错因") },
                completed.warnings.toString(),
            )
            assertTrue(transport.calls[2].userPrompt.contains("未提供解题过程"), "阶段 C 需明确说明学生过程缺失")
        }

    @Test
    @DisplayName("阶段 A 未产出题干时整体失败，不进入后续阶段")
    fun emptyStemFails() =
        runTest {
            val transport = FakeTransport()
            transport.enqueueText("{\"subject\":\"math\",\"grade\":\"高一\",\"stem\":\"\"}")
            val (gateway, _, _) = gateway(transport)

            val events = mutableListOf<SolveEvent>()
            gateway.solve(
                credentials(),
                request(),
                ModelRouter(ModelPool("qwen-plus", "qwen-vl-plus")),
            ).collect { events.add(it) }
            assertTrue(events.last() is SolveEvent.Failed)
            assertEquals(AiStage.A, (events.last() as SolveEvent.Failed).stage)
        }
}
