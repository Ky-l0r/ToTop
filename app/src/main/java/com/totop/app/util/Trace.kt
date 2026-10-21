package com.totop.app.util

import com.totop.app.domain.model.AiStage
import com.totop.app.domain.model.AiTrace

/**
 * 可注入的 UTC 时钟。
 *
 * 领域层约定所有时间戳为 UTC 毫秒 Long（开发方案 5.2），
 * 因此这里只暴露毫秒时间戳，不引入 java.time 与时区类型。
 */
fun interface UtcClock {
    fun nowUtcMillis(): Long

    companion object {
        /** 系统时钟。 */
        val SYSTEM: UtcClock = UtcClock { System.currentTimeMillis() }

        /** 固定时钟，单测用。 */
        fun fixed(millis: Long): MutableUtcClock = MutableUtcClock(millis)
    }
}

/** 可推进的测试时钟。 */
class MutableUtcClock(private var current: Long) : UtcClock {
    override fun nowUtcMillis(): Long = current

    fun set(millis: Long) {
        current = millis
    }

    fun advanceBy(millis: Long) {
        current += millis
    }
}

/** 一天的毫秒数，用于 UTC 日序号换算（与时区无关）。 */
const val DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * ai_trace 存储抽象（开发方案 5.1 ai_trace 表、6.2「每次调用写 ai_trace」）。
 *
 * 实现由 data 层提供（Room 的 AiTraceDao + 实体）；
 * 单测与「数据库不可用」降级场景使用 [InMemoryAiTraceStore]。
 *
 * 全部为挂起函数：Room DAO 本身即挂起 API，适配器无需 runBlocking。
 */
interface AiTraceStore {
    /** 写入一条追踪记录。实现必须是尽力而为：写失败不得让主链路崩溃。 */
    suspend fun insert(trace: AiTrace)

    /** 查询 [fromUtc, toUtc) 区间内的记录，按时间升序。 */
    suspend fun between(
        fromUtc: Long,
        toUtc: Long,
    ): List<AiTrace>

    /** 某次请求的全部记录（幂等去重与重试排查用）。 */
    suspend fun byRequestId(requestId: String): List<AiTrace>
}

/** 内存实现：单测与降级使用，容量上限防止无限增长。 */
class InMemoryAiTraceStore(private val capacity: Int = 2000) : AiTraceStore {
    private val items = ArrayDeque<AiTrace>()

    override suspend fun insert(trace: AiTrace) {
        items.addLast(trace)
        while (items.size > capacity) items.removeFirst()
    }

    override suspend fun between(
        fromUtc: Long,
        toUtc: Long,
    ): List<AiTrace> =
        items.filter { it.createdAtUtc >= fromUtc && it.createdAtUtc < toUtc }.sortedBy { it.createdAtUtc }

    override suspend fun byRequestId(requestId: String): List<AiTrace> =
        items.filter { it.requestId == requestId }.sortedBy { it.createdAtUtc }

    /** 当前条数（测试用）。 */
    val size: Int get() = items.size
}

/** 按阶段汇总的用量。 */
data class StageUsage(
    val stage: AiStage,
    val calls: Int,
    val failures: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val avgLatencyMs: Long,
)

/** 按 UTC 日汇总的用量（设置页近 30 天趋势图的数据点）。 */
data class DailyUsage(
    /** UTC 日序号（自 1970-01-01 起的天数）。 */
    val dateEpochDayUtc: Long,
    val calls: Int,
    val tokens: Int,
    val failures: Int,
)

/**
 * 用量汇总（开发方案 6.2：设置页展示近 30 天用量趋势）。
 *
 * 覆盖「token 用量、耗时、成功标志」三项要求，全部来自本机 ai_trace 表，不上传任何数据。
 */
data class UsageSummary(
    val fromUtc: Long,
    val toUtc: Long,
    val calls: Int = 0,
    val okCalls: Int = 0,
    val failedCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val avgLatencyMs: Long = 0L,
    val byStage: List<StageUsage> = emptyList(),
    val byDay: List<DailyUsage> = emptyList(),
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    /** 成功率（0..1）；无调用时为 0。 */
    val successRate: Double get() = if (calls == 0) 0.0 else okCalls.toDouble() / calls
}

/**
 * ai_trace 写入与用量查询。
 *
 * 规则（开发方案 6.2 / 12.1 第 4 条）：
 * - 每次模型调用（含失败、含重试）都必须落一条记录；
 * - [requestId] 为幂等键，同一次用户操作的多阶段调用共享同一 requestId；
 * - 记录内容不得包含 API Key、请求体全文或图片 base64。
 */
class TraceRecorder(
    private val store: AiTraceStore,
    private val clock: UtcClock = UtcClock.SYSTEM,
) {
    /**
     * 记录一次模型调用。
     *
     * @param requestId 客户端幂等请求 ID（UUID 字符串）
     * @param stage 阶段 A/B/C/D
     * @param model 模型名
     * @param promptVer 提示词版本，参与缓存键
     * @param inputTokens 输入 token（未知时填 0）
     * @param outputTokens 输出 token（未知时填 0）
     * @param latencyMs 端到端耗时
     * @param ok 是否成功
     * @param errorMsg 失败原因（LlmError.traceMessage()），成功时为 null
     */
    suspend fun record(
        requestId: String,
        stage: AiStage,
        model: String,
        promptVer: String,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        latencyMs: Long = 0L,
        ok: Boolean = true,
        errorMsg: String? = null,
    ): AiTrace {
        val now = clock.nowUtcMillis()
        val trace =
            AiTrace(
                // id 必须全局唯一：同一次请求同一阶段的重试也会各写一条，因此带上时间戳与序号
                id = requestId + "-" + stage.wire + "-" + now + "-" + nextSequence(),
                requestId = requestId,
                stage = stage,
                model = model,
                promptVer = promptVer,
                inputTokens = inputTokens.coerceAtLeast(0),
                outputTokens = outputTokens.coerceAtLeast(0),
                latencyMs = latencyMs.coerceAtLeast(0L),
                ok = ok,
                errorMsg = errorMsg,
                createdAtUtc = now,
            )
        runCatching { store.insert(trace) }
        return trace
    }

    /** 某次请求的全部阶段记录（幂等去重与排查）。 */
    suspend fun tracesOf(requestId: String): List<AiTrace> =
        runCatching { store.byRequestId(requestId) }.getOrDefault(emptyList())

    /**
     * 近 [days] 天用量汇总（默认 30 天，开发方案 6.2 设置页需求）。
     * 时间窗口为 [now - days*1天, now)。
     */
    suspend fun usageWithin(days: Int = 30): UsageSummary {
        require(days > 0) { "统计天数必须为正数，实际为：" + days }
        val now = clock.nowUtcMillis()
        return summarize(now - days * DAY_MILLIS, now + 1L)
    }

    /** 指定区间 [fromUtc, toUtc) 的用量汇总。 */
    suspend fun summarize(
        fromUtc: Long,
        toUtc: Long,
    ): UsageSummary {
        val traces = runCatching { store.between(fromUtc, toUtc) }.getOrDefault(emptyList())
        if (traces.isEmpty()) return UsageSummary(fromUtc = fromUtc, toUtc = toUtc)

        val okCalls = traces.count { it.ok }
        val byStage =
            AiStage.values().mapNotNull { stage ->
                val subset = traces.filter { it.stage == stage }
                if (subset.isEmpty()) {
                    null
                } else {
                    StageUsage(
                        stage = stage,
                        calls = subset.size,
                        failures = subset.count { !it.ok },
                        inputTokens = subset.sumOf { it.inputTokens },
                        outputTokens = subset.sumOf { it.outputTokens },
                        avgLatencyMs = subset.sumOf { it.latencyMs } / subset.size,
                    )
                }
            }
        val byDay =
            traces
                .groupBy { epochDayOf(it.createdAtUtc) }
                .toSortedMap()
                .map { (day, subset) ->
                    DailyUsage(
                        dateEpochDayUtc = day,
                        calls = subset.size,
                        tokens = subset.sumOf { it.totalTokens },
                        failures = subset.count { !it.ok },
                    )
                }

        return UsageSummary(
            fromUtc = fromUtc,
            toUtc = toUtc,
            calls = traces.size,
            okCalls = okCalls,
            failedCalls = traces.size - okCalls,
            inputTokens = traces.sumOf { it.inputTokens },
            outputTokens = traces.sumOf { it.outputTokens },
            avgLatencyMs = traces.sumOf { it.latencyMs } / traces.size,
            byStage = byStage,
            byDay = byDay,
        )
    }

    /**
     * UTC 日序号（自 1970-01-01 起的天数）。
     *
     * 用 Math.floorDiv 而非 Kotlin 的 /：后者对负数向零取整，
     * 会把 1970 年之前的时间戳归到错误的日期。
     */
    fun epochDayOf(utcMillis: Long): Long = Math.floorDiv(utcMillis, DAY_MILLIS)

    private companion object {
        /** 同一毫秒内的自增序号，保证 ai_trace.id 唯一。 */
        private val sequence = java.util.concurrent.atomic.AtomicLong(0)

        fun nextSequence(): Long = sequence.incrementAndGet()
    }
}
