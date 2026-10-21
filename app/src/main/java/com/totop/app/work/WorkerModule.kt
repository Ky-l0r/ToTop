package com.totop.app.work

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 后台任务的依赖注入（开发方案第五章）。
 *
 * 绑定两件事：
 * - [OutboxDispatcher] -> [NoOpOutboxDispatcher]：本应用无后端，补传只做本地动作；
 * - [BackgroundScheduler]：统一登记四个周期任务，UI 与 App 启动都通过它触发。
 *
 * 关于四个 `@HiltWorker` 的注入：`HiltWorkerFactory` 由 hilt-work 库的
 * `@Inject` 构造函数自动提供，**不要**在这里再 provide 一次（会重复绑定）。
 * 但 Application 必须实现 `Configuration.Provider` 并在 `workManagerConfiguration`
 * 里返回带 factory 的配置，否则 Worker 在运行时会因无法注入而崩溃 ——
 * 该接线在 [com.totop.app.App] 中完成。
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule {
    /** 默认补传执行者：本地动作无远端确认，直接视为成功（幂等）。 */
    @Provides
    @Singleton
    fun provideOutboxDispatcher(): OutboxDispatcher = NoOpOutboxDispatcher()

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideBackgroundScheduler(workManager: WorkManager): BackgroundScheduler = BackgroundScheduler(workManager)
}
