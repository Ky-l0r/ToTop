package com.totop.app.data.remote

import com.totop.app.domain.model.AiQuestion
import com.totop.app.domain.model.AiSolutionStep
import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiStandardSolution
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 提示词拼装与阶段边界测试（开发方案 6.3）。
 *
 * 三条规定必须由测试锁死：
 * 1. 阶段 B 不得注入学生过程；
 * 2. 阶段 C 必须注入学生过程；
 * 3. 阶段 D 必须注入候选知识点清单。
 */
class PromptRepositoryTest {
    private val repository = PromptRepository(AssetReader.of(TestPrompts.FILES))

    private val question =
        AiQuestion(
            subject = "math",
            grade = "高一",
            stem = "求 f(x)=x^2-2x+1 在 [0,3] 上的最小值",
            known = listOf("f(x)=x^2-2x+1"),
            ask = "求最小值",
            options = emptyList(),
            hasFigure = false,
        )

    private val solution =
        AiStandardSolution(
            steps = listOf(AiSolutionStep(1, "配方", "顶点式")),
            finalAnswer = "0",
            method = "配方法",
        )

    @Test
    @DisplayName("promptVersion 来自 assets/prompts/prompt_version.json")
    fun promptVersionFromAsset() {
        assertEquals("1.0.0", repository.promptVersion())
        assertEquals(4, repository.stageFiles().size)
        assertEquals("stage_a_identify.md", repository.stageFiles()[AiStage.A])
    }

    @Test
    @DisplayName("阶段 B 输入中不含任何学生过程字段")
    fun stageBNeverSeesStudentProcess() {
        val prompt = repository.assemble(StageInput.Solve(question), requestId = "req-1")
        assertFalse(prompt.userPrompt.contains("studentRawText"))
        assertFalse(prompt.userPrompt.contains("studentSteps"))
        assertTrue(PromptGuard.STUDENT_PROCESS_KEYS.none { prompt.payloadKeys.contains(it) })
        assertTrue(prompt.payloadKeys.contains("question"))
    }

    @Test
    @DisplayName("阶段 C 输入必须包含学生过程与标准解答")
    fun stageCRequiresStudentProcess() {
        val prompt =
            repository.assemble(
                StageInput.LocateError(question, "我直接代入 x=0", solution),
                requestId = "req-1",
            )
        assertTrue(prompt.payloadKeys.containsAll(setOf("question", "standardSolution", "studentRawText")))
        assertTrue(prompt.userPrompt.contains("我直接代入 x=0"))
    }

    @Test
    @DisplayName("阶段 D 输入必须包含候选知识点清单")
    fun stageDRequiresCandidates() {
        val prompt =
            repository.assemble(
                StageInput.AssignKpoints(
                    question = question,
                    standardSolution = solution,
                    candidates = listOf(CandidateKpoint("KP_MATH_0001", "二次函数", "函数")),
                ),
                requestId = "req-1",
            )
        assertTrue(prompt.payloadKeys.contains("candidateKpoints"))
        assertTrue(prompt.userPrompt.contains("KP_MATH_0001"))
        assertTrue(prompt.userPrompt.contains("二次函数"))
    }

    @Test
    @DisplayName("PromptGuard 对缺字段与越界注入都报错，且是中文描述")
    fun guardViolations() {
        val missing = PromptGuard.violations(AiStage.C, setOf("question"))
        assertTrue(missing.isNotEmpty())
        assertTrue(missing.first().contains("缺少必需输入"))

        val forbidden = PromptGuard.violations(AiStage.B, setOf("question", "studentRawText"))
        assertTrue(forbidden.any { it.contains("禁止注入学生过程字段") })

        assertThrows(IllegalArgumentException::class.java) {
            PromptGuard.requireValid(AiStage.D, setOf("question"))
        }
    }

    @Test
    @DisplayName("严格模式追加硬约束文案，用于 FR-1.10 的第二次尝试")
    fun strictPromptAddsConstraints() {
        val normal = repository.assemble(StageInput.Solve(question))
        val strict = repository.assemble(StageInput.Solve(question), strict = true)
        assertFalse(normal.userPrompt.contains("上一次输出无法被解析"))
        assertTrue(strict.userPrompt.contains("上一次输出无法被解析"))
        assertTrue(strict.strict)
        assertTrue(strict.userPrompt.contains("整个回复只包含一个 JSON 对象"))
    }

    @Test
    @DisplayName("system 消息为阶段模板全文，user 消息为 JSON 输入 + 输出约束")
    fun messageShape() {
        val prompt = repository.assemble(StageInput.Identify(ocrText = "题干", attachImage = true), requestId = "req-9")
        val messages = prompt.asMessages()
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.ROLE_SYSTEM, messages[0].role)
        assertEquals(ChatMessage.ROLE_USER, messages[1].role)
        assertTrue(prompt.systemPrompt.contains("阶段 A"))
        assertTrue(prompt.userPrompt.contains("\"ocrText\""))
        assertTrue(prompt.userPrompt.contains("只输出阶段 A 的 JSON"))
        assertTrue(prompt.userPrompt.contains("client_request_id: req-9"))
    }

    @Test
    @DisplayName("requestId 为空时不写入无意义的空行")
    fun emptyRequestIdOmitted() {
        val prompt = repository.assemble(StageInput.Solve(question))
        assertFalse(prompt.userPrompt.contains("client_request_id"))
    }

    @Test
    @DisplayName("模板缺失时抛出可定位的错误")
    fun missingTemplateFails() {
        val broken =
            PromptRepository(
                AssetReader.of(
                    mapOf("prompts/prompt_version.json" to TestPrompts.FILES["prompts/prompt_version.json"]!!),
                ),
            )
        val error = assertThrows(IllegalStateException::class.java) { broken.template(AiStage.A) }
        assertTrue(error.message.orEmpty().contains("stage_a_identify.md"))
    }
}

/** 双层缓存测试（挂起 API，故用 runTest 包裹）。 */
class LlmCacheTest {
    private fun key(
        model: String = "qwen-plus",
        promptVersion: String = "1.0.0",
    ) = CacheKey(sha256 = "abc123", promptVersion = promptVersion, model = model)

    @Test
    @DisplayName("写入后可命中，命中后刷新访问时间")
    fun putAndGet() =
        runTest {
            val cache = LlmCache(InMemoryCacheStore(), now = { 1_000L })
            cache.put(key(), "{\"a\":1}")
            assertEquals("{\"a\":1}", cache.get(key()))
            assertEquals(1L, cache.hitCount())
            assertEquals(0L, cache.missCount())
        }

    @Test
    @DisplayName("缓存键三个维度任一不同都不命中（sha256 / promptVersion / model）")
    fun keyDimensions() =
        runTest {
            val cache = LlmCache(InMemoryCacheStore(), now = { 1_000L })
            cache.put(key(), "payload")
            assertEquals(null, cache.get(key(model = "other-model")))
            assertEquals(null, cache.get(key(promptVersion = "1.0.1")))
            assertEquals(null, cache.get(CacheKey("other-sha", "1.0.0", "qwen-plus")))
            assertEquals("payload", cache.get(key()))
        }

    @Test
    @DisplayName("超出 TTL 视为未命中并清理")
    fun ttlExpiry() =
        runTest {
            var now = 0L
            val cache = LlmCache(InMemoryCacheStore(), ttlMillis = 1_000L, now = { now })
            cache.put(key(), "payload")
            now = 900L
            assertEquals("payload", cache.get(key()))
            now = 2_000L
            assertEquals(null, cache.get(key()))
        }

    /** 只服务内存层测试的 store：磁盘层永远未命中，从而单测内存 LRU 的淘汰顺序。 */
    private class EmptyCacheStore : CacheStore {
        override suspend fun get(storageKey: String): CacheEntry? = null

        override suspend fun put(entry: CacheEntry) = Unit

        override suspend fun entriesByLastAccess(): List<CacheEntry> = emptyList()

        override suspend fun delete(storageKey: String) = Unit

        override suspend fun totalSizeBytes(): Long = 0L

        override suspend fun clear() = Unit
    }

    @Test
    @DisplayName("内存层超过上限时按 LRU 淘汰最久未用项")
    fun memoryLruEviction() =
        runTest {
            val small = LlmCache(EmptyCacheStore(), memoryLimitBytes = 20L, now = { 0L })
            small.put(CacheKey("k1", "1.0.0", "m"), "aaaaaaaaaa")
            small.put(CacheKey("k2", "1.0.0", "m"), "bbbbbbbbbb")
            assertTrue(small.memorySizeBytes() <= 20L)
            small.put(CacheKey("k3", "1.0.0", "m"), "cccccccccc")
            assertTrue(small.memorySizeBytes() <= 20L, small.memorySizeBytes().toString())
            assertEquals(null, small.get(CacheKey("k1", "1.0.0", "m")), "最久未用的 k1 应被淘汰")
            assertEquals("cccccccccc", small.get(CacheKey("k3", "1.0.0", "m")))
        }

    @Test
    @DisplayName("内存层淘汰后仍可从磁盘层命中（双层缓存的意义）")
    fun diskSurvivesMemoryEviction() =
        runTest {
            val store = InMemoryCacheStore()
            val cache = LlmCache(store, memoryLimitBytes = 1L, now = { 0L })
            cache.put(CacheKey("k1", "1.0.0", "m"), "persisted")
            assertEquals("persisted", cache.get(CacheKey("k1", "1.0.0", "m")))
            assertEquals(1, store.size)
        }

    @Test
    @DisplayName("invalidate 与 clear 同时作用于两层")
    fun invalidateAndClear() =
        runTest {
            val store = InMemoryCacheStore()
            val cache = LlmCache(store, now = { 0L })
            cache.put(key(), "payload")
            cache.invalidate(key())
            assertEquals(null, cache.get(key()))
            assertEquals(0, store.size)

            cache.put(key(), "payload-2")
            cache.clear()
            assertEquals(null, cache.get(key()))
            assertEquals(0L, cache.memorySizeBytes())
        }

    @Test
    @DisplayName("磁盘层超过上限时按 lastAccessAt 淘汰")
    fun diskEviction() =
        runTest {
            val store = InMemoryCacheStore()
            var now = 0L
            val cache = LlmCache(store, diskLimitBytes = 15L, now = { now })
            cache.put(CacheKey("k1", "1.0.0", "m"), "aaaaa")
            now = 10L
            cache.put(CacheKey("k2", "1.0.0", "m"), "bbbbb")
            now = 20L
            cache.put(CacheKey("k3", "1.0.0", "m"), "ccccc")
            assertTrue(store.totalSizeBytes() <= 15L, store.totalSizeBytes().toString())
        }

    @Test
    @DisplayName("存储键格式为 sha256|promptVersion|model，可直接作为 Room 主键")
    fun storageKeyFormat() {
        assertEquals("abc123|1.0.0|qwen-plus", key().storageKey)
        assertThrows(IllegalArgumentException::class.java) { CacheKey("", "1.0.0", "m") }
    }
}
