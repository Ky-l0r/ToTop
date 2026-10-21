package com.totop.app.data.repo

import com.totop.app.data.local.dao.OutboxDao
import com.totop.app.data.local.entity.OutboxEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 离线写队列仓库（开发方案 5.1 `outbox` / FR-8.5）。
 *
 * 补传策略（FR-8.2）：指数退避 1s / 2s / 4s，最多 3 次；超过次数即视为放弃并出队。
 * 退避计算是纯函数 [backoffDelayMs]，可单测。
 */
@Singleton
class OutboxRepository
    @Inject
    constructor(
        private val dao: OutboxDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): OutboxRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun enqueue(
            id: String,
            payloadJson: String,
            nowUtc: Long = System.currentTimeMillis(),
        ) = withContext(dispatcher) {
            dao.upsert(
                OutboxEntity(
                    id = id,
                    payloadJson = payloadJson,
                    attempts = 0,
                    nextRetryAt = nowUtc,
                    createdAt = nowUtc,
                ),
            )
        }

        suspend fun due(
            nowUtc: Long = System.currentTimeMillis(),
            limit: Int = DEFAULT_BATCH,
        ): List<OutboxEntity> = withContext(dispatcher) { dao.due(nowUtc, limit) }

        fun observeDue(
            nowUtc: Long,
            limit: Int = DEFAULT_BATCH,
        ): Flow<List<OutboxEntity>> = dao.observeDue(nowUtc, limit).flowOn(dispatcher)

        suspend fun count(): Int = withContext(dispatcher) { dao.count() }

        fun observeCount(): Flow<Int> = dao.observeCount().flowOn(dispatcher)

        /**
         * 标记一次失败：attempts + 1，并按指数退避计算下次重试时间。
         *
         * 达到 [MAX_ATTEMPTS] 次即视为放弃，直接出队。
         *
         * @return 是否仍留在队列中（false 表示已放弃并删除）。
         */
        suspend fun markFailed(
            id: String,
            nowUtc: Long = System.currentTimeMillis(),
        ): Boolean =
            withContext(dispatcher) {
                val current = dao.findById(id) ?: return@withContext false
                val attempts = current.attempts + 1
                if (attempts >= MAX_ATTEMPTS) {
                    dao.deleteById(id)
                    false
                } else {
                    dao.updateAttempt(id, attempts, nowUtc + backoffDelayMs(attempts))
                    true
                }
            }

        /** 补传成功：出队。 */
        suspend fun markDone(id: String): Boolean = withContext(dispatcher) { dao.deleteById(id) > 0 }

        suspend fun clearExhausted(): Int = withContext(dispatcher) { dao.deleteExhausted(MAX_ATTEMPTS) }

        suspend fun clear() = withContext(dispatcher) { dao.deleteAll() }

        companion object {
            /** 最大尝试次数（FR-8.2：1s / 2s / 4s，最多 3 次）。 */
            const val MAX_ATTEMPTS: Int = 3

            /** 退避基数：1 秒。 */
            const val BASE_DELAY_MS: Long = 1_000L

            /** 单批补传条数。 */
            const val DEFAULT_BATCH: Int = 20

            /**
             * 指数退避（纯函数）：第 1 次失败 1s、第 2 次 2s、第 3 次 4s，封顶 [MAX_DELAY_MS]。
             *
             * @param failedAttempts 已失败次数（从 1 开始）
             */
            fun backoffDelayMs(failedAttempts: Int): Long {
                if (failedAttempts <= 0) return BASE_DELAY_MS
                val exponent = min(failedAttempts - 1, MAX_BACKOFF_EXPONENT)
                return min(BASE_DELAY_MS shl exponent, MAX_DELAY_MS)
            }

            /** 退避指数的上限，避免位移溢出。 */
            const val MAX_BACKOFF_EXPONENT: Int = 5

            /** 退避上限 32 秒。 */
            const val MAX_DELAY_MS: Long = 32_000L
        }
    }
