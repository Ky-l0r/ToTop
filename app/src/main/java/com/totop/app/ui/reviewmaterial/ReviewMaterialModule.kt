package com.totop.app.ui.reviewmaterial

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 复习资料的依赖装配。
 *
 * 列表页与详情页都要用 [ReviewMaterialStore]（列表读摘要、详情读全文），
 * 导出链路（[ReviewMaterialPdfWriter] + [ExportSink]）归详情页。
 * 三者都不持有页面状态，进程内单例即可；此前在 ViewModel 里直接 new，
 * 拆成两个页面后没了共同的宿主，所以提为可注入。
 */
@Module
@InstallIn(SingletonComponent::class)
object ReviewMaterialModule {
    @Provides
    @Singleton
    fun provideReviewMaterialStore(
        @ApplicationContext context: Context,
    ): ReviewMaterialStore = ReviewMaterialStore(context)

    @Provides
    @Singleton
    fun provideReviewMaterialPdfWriter(): ReviewMaterialPdfWriter = ReviewMaterialPdfWriter()

    @Provides
    @Singleton
    fun provideExportSink(
        @ApplicationContext context: Context,
    ): ExportSink = ExportSink(context)
}
