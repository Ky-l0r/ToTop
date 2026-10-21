package com.totop.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.totop.app.work.BackgroundScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 架构约束（《AI 辅助学习 App 开发方案》C2/C6）：
 * 无自建后端，全部数据留在本机；此处仅完成 Hilt 依赖图初始化，
 * 禁止在此处加入任何统计、广告、埋点 SDK 初始化。
 *
 * 后台任务接线：实现 [Configuration.Provider] 并注入 [HiltWorkerFactory]，
 * 让四个 `@HiltWorker`（DailyStatsWorker / RetryWorker / CacheCleanWorker / ReviewReminderWorker）
 * 能在 WorkManager 实例化时拿到依赖。这是 Hilt 的硬性要求：
 * 少了这一步，Worker 一跑就会因为找不到构造函数而崩溃。
 *
 * 排期在 [onCreate] 里登记一次（[BackgroundScheduler.scheduleAll] 内部用
 * `ExistingPeriodicWorkPolicy.KEEP`，重复调用不会重置已有任务）。
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var scheduler: BackgroundScheduler

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()

    override fun onCreate() {
        super.onCreate()
        // 登记每日汇总 / 缓存清理 / 复习提醒；失败不影响主流程（例如用户禁用后台任务）
        runCatching { scheduler.scheduleAll() }
    }
}
