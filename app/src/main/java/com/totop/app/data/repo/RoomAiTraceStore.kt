package com.totop.app.data.repo

import com.totop.app.domain.model.AiTrace
import com.totop.app.util.AiTraceStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiTraceStore] 的 Room 实现：把 util 层的 ai_trace 抽象接到 `ai_trace` 表（开发方案 5.1 / 6.2）。
 *
 * 由 ai 模块在 LlmModule 中用本类替换默认的 `InMemoryAiTraceStore`，
 * 即满足「每次调用写 ai_trace，设置页展示近 30 天用量趋势」的落库要求。
 *
 * 失败策略（方案 6.2）：写入是尽力而为——DAO 异常被吞掉，
 * 追踪数据写不进去也绝不影响解题主链路；读取异常返回空集合。
 */
@Singleton
class RoomAiTraceStore
    @Inject
    constructor(
        private val repository: AiTraceRepository,
    ) : AiTraceStore {
        override suspend fun insert(trace: AiTrace) {
            runCatching { repository.record(trace) }
        }

        override suspend fun between(
            fromUtc: Long,
            toUtc: Long,
        ): List<AiTrace> = runCatching { repository.between(fromUtc, toUtc) }.getOrDefault(emptyList())

        override suspend fun byRequestId(requestId: String): List<AiTrace> =
            runCatching { repository.byRequestId(requestId)?.let { listOf(it) }.orEmpty() }.getOrDefault(emptyList())
    }
