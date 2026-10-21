package com.totop.app.work

import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台任务登记（开发方案第五章 / FR-4.4 / FR-8.5 / FR-8.4）。
 *
 * 四个 Worker 的分工与触发时机（全部本地执行，**不联网、不上传**）：
 *
 * | Worker | 触发 | 作用 |
 * | --- | --- | --- |
 * | [DailyStatsWorker] | 每日一次（夜间）+ 打开 App 时补一次 | UPSERT daily_stats + 重算掌握度 |
 * | [RetryWorker] | 联网时 / 每次入队 outbox 后 | 补传离线写操作（指数退避 1s/2s/4s） |
 * | [CacheCleanWorker] | 每日一次（低电量优先不跑）| 磁盘 LRU 清理，上限 100MB |
 * | [ReviewReminderWorker] | 每日固定时刻 | 简化 SM-2 排期 + 本地通知 |
 *
 * 设计约束：
 * - 全部是 [CoroutineWorker]，注入由 Hilt 的 `@HiltWorker` + `HiltWorkerFactory` 完成
 *   （见 [WorkerModule] 与 App.kt 的 Configuration.Provider）；
 * - 周期性任务的间隔不低于 15 分钟（WorkManager 硬限制）；
 * - 每个 Worker 都必须**幂等**：重复执行不会产生重复数据、不会重复弹通知；
 * - 任何失败都返回 [Result.success] 或 [Result.retry]，绝不 crash 应用。
 */
object WorkerNames {
    /** 每日汇总。 */
    const val DAILY_STATS: String = "totop-daily-stats"

    /** outbox 补传。 */
    const val RETRY_OUTBOX: String = "totop-retry-outbox"

    /** 缓存清理。 */
    const val CACHE_CLEAN: String = "totop-cache-clean"

    /** 复习提醒。 */
    const val REVIEW_REMINDER: String = "totop-review-reminder"

    /** 全部周期性任务名（App 启动时统一登记）。 */
    val PERIODIC: List<String> = listOf(DAILY_STATS, CACHE_CLEAN, REVIEW_REMINDER)
}

/** 后台任务排期器。 */
class BackgroundScheduler(private val workManager: WorkManager) {
    /**
     * 登记全部周期性任务（App 冷启动调用一次）。
     *
     * 用 [ExistingPeriodicWorkPolicy.KEEP]：已存在的任务不重建，
     * 避免用户每次开 App 都把执行时间往后推。
     */
    fun scheduleAll() {
        scheduleDailyStats()
        scheduleCacheClean()
        scheduleReviewReminder()
    }

    /** 每日汇总：夜间 3 点前后执行一次（系统会为省电做批量调度，不保证精确时刻）。 */
    fun scheduleDailyStats() {
        val request =
            PeriodicWorkRequestBuilder<DailyStatsWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(3, 0), TimeUnit.MILLISECONDS)
                .setConstraints(NO_NETWORK_NEEDED)
                .addTag(WorkerNames.DAILY_STATS)
                .build()
        workManager.enqueueUniquePeriodicWork(
            WorkerNames.DAILY_STATS,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 缓存清理：每日一次，充电时优先。 */
    fun scheduleCacheClean() {
        val constraints =
            Constraints.Builder()
                .setRequiresCharging(false)
                .setRequiresBatteryNotLow(true)
                .build()
        val request =
            PeriodicWorkRequestBuilder<CacheCleanWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(4, 0), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(WorkerNames.CACHE_CLEAN)
                .build()
        workManager.enqueueUniquePeriodicWork(
            WorkerNames.CACHE_CLEAN,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 复习提醒：每日固定时刻（默认本地时间 20:00 附近）。 */
    fun scheduleReviewReminder(hourOfDay: Int = REVIEW_HOUR) {
        val request =
            PeriodicWorkRequestBuilder<ReviewReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelayMillis(hourOfDay, 0), TimeUnit.MILLISECONDS)
                .setConstraints(NO_NETWORK_NEEDED)
                .addTag(WorkerNames.REVIEW_REMINDER)
                .build()
        workManager.enqueueUniquePeriodicWork(
            WorkerNames.REVIEW_REMINDER,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** outbox 补传：联网时立即跑一次（一次性任务，不排队重复）。 */
    fun requestOutboxRetry() {
        val request =
            OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .addTag(WorkerNames.RETRY_OUTBOX)
                .build()
        workManager.enqueueUniqueWork(WorkerNames.RETRY_OUTBOX, ExistingWorkPolicy.KEEP, request)
    }

    /** 立即跑一次汇总（用户手动点「立即更新统计」）。 */
    fun requestDailyStatsNow() {
        val request =
            OneTimeWorkRequestBuilder<DailyStatsWorker>()
                .addTag(WorkerNames.DAILY_STATS)
                .build()
        workManager.enqueueUniqueWork(WorkerNames.DAILY_STATS + "-now", ExistingWorkPolicy.REPLACE, request)
    }

    /** 取消全部后台任务（用户关闭「后台任务」开关）。 */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(WorkerNames.DAILY_STATS)
        workManager.cancelAllWorkByTag(WorkerNames.CACHE_CLEAN)
        workManager.cancelAllWorkByTag(WorkerNames.REVIEW_REMINDER)
    }

    /**
     * 计算到「今天 [hour]:[minute]」的初始延迟；若今天已过该时刻则顺延到明天。
     *
     * 纯计算，但依赖系统时区（用户看到的是本地时间），因此放在这里而不在 Worker 里。
     */
    fun initialDelayMillis(
        hour: Int,
        minute: Int,
        nowUtc: Long = System.currentTimeMillis(),
    ): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = nowUtc
        val target =
            (calendar.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        var delay = target.timeInMillis - nowUtc
        if (delay <= 0L) delay += TimeUnit.DAYS.toMillis(1)
        return delay
    }

    companion object {
        /** 复习提醒的默认小时（本地时间）。 */
        const val REVIEW_HOUR: Int = 20

        /** 本地任务，不需要网络约束。 */
        private val NO_NETWORK_NEEDED: Constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()
    }
}
