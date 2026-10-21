package com.totop.app.data.repo

import com.totop.app.data.local.dao.AiTraceDao
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.AiTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 调用追踪仓库（开发方案 5.1 `ai_trace` / 6.2 设置页近 30 天用量趋势）。
 *
 * ai 模块每次调用都必须写一条 trace；本仓库只负责存取与聚合，不做业务判断。
 * requestId 唯一，重复写入由 @Upsert 覆盖（同一次请求的重试不会产生两行）。
 */
@Singleton
class AiTraceRepository
    @Inject
    constructor(
        private val dao: AiTraceDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): AiTraceRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun record(trace: AiTrace) = withContext(dispatcher) { dao.upsert(trace.toEntity()) }

        suspend fun byRequestId(requestId: String): AiTrace? =
            withContext(dispatcher) {
                dao.findByRequestId(requestId)?.let { with(EntityMappers) { it.toDomain() } }
            }

        suspend fun page(request: PageRequest): List<AiTrace> =
            withContext(dispatcher) {
                dao.page(request.size, request.offset).map { with(EntityMappers) { it.toDomain() } }
            }

        fun observePage(request: PageRequest): Flow<List<AiTrace>> =
            dao.observePage(request.size, request.offset)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        suspend fun between(
            fromUtc: Long,
            toUtc: Long,
        ): List<AiTrace> =
            withContext(dispatcher) {
                dao.between(fromUtc, toUtc).map { with(EntityMappers) { it.toDomain() } }
            }

        fun observeBetween(
            fromUtc: Long,
            toUtc: Long,
        ): Flow<List<AiTrace>> =
            dao.observeBetween(fromUtc, toUtc)
                .map { rows -> rows.map { with(EntityMappers) { it.toDomain() } } }
                .flowOn(dispatcher)

        /** 近 N 天用量汇总。 */
        suspend fun usageSummary(
            fromUtc: Long,
            toUtc: Long,
        ): AiUsageSummary =
            withContext(dispatcher) {
                AiUsageSummary(
                    inputTokens = dao.sumInputTokens(fromUtc, toUtc),
                    outputTokens = dao.sumOutputTokens(fromUtc, toUtc),
                    calls = dao.countBetween(fromUtc, toUtc),
                    okCalls = dao.countOk(fromUtc, toUtc),
                )
            }

        /** 清理过期 trace，控制数据库体积。 */
        suspend fun purgeOlderThan(keepFromUtc: Long): Int =
            withContext(dispatcher) { dao.deleteOlderThan(keepFromUtc) }

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }

        /** 用量汇总。 */
        data class AiUsageSummary(
            val inputTokens: Int,
            val outputTokens: Int,
            val calls: Int,
            val okCalls: Int,
        ) {
            val totalTokens: Int get() = inputTokens + outputTokens

            /** 成功率 0..1；无调用时为 0。 */
            val successRate: Double get() = if (calls == 0) 0.0 else okCalls.toDouble() / calls
        }

        companion object {
            /** 用量趋势默认窗口：近 30 天（方案 6.2）。 */
            const val TREND_DAYS: Int = 30
        }
    }
