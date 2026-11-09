package com.totop.app.ui.study

import com.totop.app.domain.usecase.port.TrendNarrator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 学情页的依赖装配：只把解读实现绑到端口，其余依赖由既有模块提供。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StudyModule {
    @Binds
    @Singleton
    abstract fun bindTrendNarrator(impl: TrendNarratorImpl): TrendNarrator
}
