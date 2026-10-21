package com.totop.app.ui.mistakes

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 复习页的依赖装配（Hilt）。
 *
 * [RoomReviewGateway] 需要 RoomDatabase 与错题仓库，构造参数带默认值，
 * 因此这里用 @Provides 显式构造；上层只看到 [ReviewGateway] 接口。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReviewModule {
    @Binds
    @Singleton
    abstract fun bindReviewGateway(impl: RoomReviewGateway): ReviewGateway

    companion object {
        /** 复习网关的构造（时钟默认系统时钟，单测可替换）。 */
        @Provides
        @Singleton
        fun provideRoomReviewGateway(
            database: com.totop.app.data.local.AppDatabase,
            mistakeRepository: com.totop.app.data.repo.MistakeRepository,
            clock: com.totop.app.domain.usecase.port.DomainClock,
        ): RoomReviewGateway =
            RoomReviewGateway(
                database = database,
                mistakeRepository = mistakeRepository,
                clock = clock,
            )

        /**
         * 领域时钟。
         *
         * domain 层的端口是纯接口（便于单测注入固定时钟），Hilt 无法自动提供，
         * 因此在 ui 层的模块里给出唯一的系统时钟实例，不触碰 domain 与 data 的既有定义。
         */
        @Provides
        @Singleton
        fun provideDomainClock(): com.totop.app.domain.usecase.port.DomainClock =
            com.totop.app.domain.usecase.port.DomainClock.SYSTEM
    }
}
