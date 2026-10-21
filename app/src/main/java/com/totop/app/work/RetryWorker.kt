package com.totop.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.totop.app.data.local.dao.OutboxDao
import com.totop.app.data.repo.OutboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * outbox 补传 Worker（开发方案 FR-8.5：离线写操作走 outbox 队列，联网后按序补传）。
 *
 * 补传语义（与 [OutboxRepository] 保持一致，**不重复实现退避算法**）：
 * - 取出到期条目（按入队顺序，单批 [OutboxRepository.DEFAULT_BATCH] 条）；
 * - 逐条交给 [OutboxDispatcher] 执行；成功则出队，失败则 [OutboxRepository.markFailed]
 *   按 1s / 2s / 4s 退避并计入 attempts，达到上限自动放弃；
 * - 仍有失败条目时返回 [Result.retry]，让 WorkManager 在网络可用时再来一次。
 *
 * 注意：本项目是 BYOK + 无后端架构，**没有服务端可补传**。
 * outbox 的现实用途是「本地异步副作用」（例如批量重算掌握度、写导出审计），
 * 因此 [OutboxDispatcher] 是一个可注入的接口，默认实现只做本地动作。
 */
@HiltWorker
class RetryWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val outboxDao: OutboxDao,
        private val outbox: OutboxRepository,
        private val dispatcher: OutboxDispatcher,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val nowUtc = System.currentTimeMillis()
            val due =
                runCatching { outboxDao.due(nowUtc, OutboxRepository.DEFAULT_BATCH) }.getOrNull()
                    ?: return Result.retry()
            if (due.isEmpty()) return Result.success()

            var hasFailure = false
            for (entity in due) {
                val handled = runCatching { dispatcher.dispatch(entity.id, entity.payloadJson) }.getOrDefault(false)
                if (handled) {
                    outbox.markDone(entity.id)
                } else {
                    hasFailure = true
                    outbox.markFailed(entity.id, nowUtc)
                }
            }
            return if (hasFailure) Result.retry() else Result.success()
        }

        companion object {
            /** 单次补传的退避常量直接复用仓库定义，避免两处不一致。 */
            const val MAX_ATTEMPTS: Int = OutboxRepository.MAX_ATTEMPTS
        }
    }

/**
 * outbox 条目的实际执行者。
 *
 * 默认实现 [NoOpOutboxDispatcher] 只记录并返回成功：本应用没有后端，
 * 队列里的条目通常已经在本地落库完成，这里的作用是「让队列能正常排空」。
 * 将来若接入设备间同步，只需替换此实现，Worker 逻辑不用改。
 */
fun interface OutboxDispatcher {
    /**
     * @param id 条目 id
     * @param payloadJson 载荷
     * @return 是否处理成功（false 会触发退避重试）
     */
    suspend fun dispatch(
        id: String,
        payloadJson: String,
    ): Boolean
}

/** 默认实现：本地动作无需远端确认，直接视为成功（幂等）。 */
class NoOpOutboxDispatcher : OutboxDispatcher {
    override suspend fun dispatch(
        id: String,
        payloadJson: String,
    ): Boolean = true
}
