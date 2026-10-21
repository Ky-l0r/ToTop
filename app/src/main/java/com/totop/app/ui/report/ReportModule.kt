package com.totop.app.ui.report

import com.totop.app.data.local.AppDatabase
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.LlmClient
import com.totop.app.data.repo.DailyStatsRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.MistakeRepository
import com.totop.app.data.repo.ReportRepository
import com.totop.app.data.repo.SettingsRepository
import com.totop.app.domain.usecase.BuildWeeklyReportUseCase
import com.totop.app.domain.usecase.port.IdGenerator
import com.totop.app.domain.usecase.port.ReportNarrator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 周报页的依赖装配（Hilt）。
 *
 * 目的：把既有 Repository 适配成 domain 用例需要的端口，并组装
 * [BuildWeeklyReportUseCase]。端口实现全部在本包内（Room*Store / RoomDomainSettings），
 * 不改动 data 层的任何既有定义。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReportModule {
    /** 报告 AI 解读端口：实现只用 LlmClient 门面，不直接构造 OkHttp。 */
    @Binds
    @Singleton
    abstract fun bindReportNarrator(impl: ReportNarratorImpl): ReportNarrator

    companion object {
        @Provides
        @Singleton
        fun provideRoomMistakeStore(
            database: AppDatabase,
            mistakeRepository: MistakeRepository,
        ): RoomMistakeStore = RoomMistakeStore(mistakeRepository = mistakeRepository, database = database)

        @Provides
        @Singleton
        fun provideRoomDailyStatsStore(repository: DailyStatsRepository): RoomDailyStatsStore =
            RoomDailyStatsStore(repository)

        @Provides
        @Singleton
        fun provideRoomKpointDirectory(repository: KnowledgeRepository): RoomKpointDirectory =
            RoomKpointDirectory(repository)

        @Provides
        @Singleton
        fun provideRoomReportStore(repository: ReportRepository): RoomReportStore = RoomReportStore(repository)

        @Provides
        @Singleton
        fun provideRoomDomainSettings(
            repository: SettingsRepository,
            secretStore: SecretStore,
        ): RoomDomainSettings = RoomDomainSettings(repository = repository, secretStore = secretStore)

        /** 报告用例：所有统计与渲染的唯一实现点。 */
        @Provides
        @Singleton
        fun provideBuildWeeklyReportUseCase(
            dailyStats: RoomDailyStatsStore,
            mistakes: RoomMistakeStore,
            kpoints: RoomKpointDirectory,
            reports: RoomReportStore,
            narrator: ReportNarrator,
            settings: RoomDomainSettings,
            clock: com.totop.app.domain.usecase.port.DomainClock,
        ): BuildWeeklyReportUseCase =
            BuildWeeklyReportUseCase(
                dailyStats = dailyStats,
                mistakes = mistakes,
                kpoints = kpoints,
                reports = reports,
                narrator = narrator,
                settings = settings,
                clock = clock,
                ids = IdGenerator.UUID_GENERATOR,
            )

        /** 用例工厂（ViewModel 每次都取同一个用例实例，其内部无状态）。 */
        @Provides
        @Singleton
        fun provideReportUseCaseFactory(useCase: BuildWeeklyReportUseCase): ReportUseCaseFactory =
            ReportUseCaseFactory(useCase)

        /**
         * 报告 AI 解读实现的构造（显式提供，便于单测替换 SecretStore / LlmClient）。
         *
         * LlmClient 在这里给出进程内单例：只用于「周报解读」这一次短文本生成，
         * 与解题页的网关互不影响；它内部已是 SSE + 超时 + 错误分类的完整实现，
         * ViewModel 与 UseCase 都看不到 OkHttp。
         */
        @Provides
        @Singleton
        fun provideReportNarratorImpl(
            secretStore: SecretStore,
            llmClient: LlmClient,
        ): ReportNarratorImpl = ReportNarratorImpl(secretStore = secretStore, llmClient = llmClient)

        /** 周报解读用的 LLM 客户端（进程内单例，独立连接池）。 */
        @Provides
        @Singleton
        fun provideReportLlmClient(): LlmClient = LlmClient()
    }
}
