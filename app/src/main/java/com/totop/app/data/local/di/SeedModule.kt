package com.totop.app.data.local.di

import android.content.Context
import android.content.res.AssetManager
import com.totop.app.data.local.seed.AssetsSeedAssetReader
import com.totop.app.data.local.seed.SeedAssetReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 种子资产读取的依赖注入。
 *
 * 把 AssetManager 包装成 [SeedAssetReader] 接口，SeedRepository 因此可以在
 * 纯 JVM 单测中用内存实现替换，不需要 Robolectric。
 */
@Module
@InstallIn(SingletonComponent::class)
object SeedModule {
    @Provides
    @Singleton
    fun provideAssetManager(
        @ApplicationContext context: Context,
    ): AssetManager = context.assets

    @Provides
    @Singleton
    fun provideSeedAssetReader(assetManager: AssetManager): SeedAssetReader = AssetsSeedAssetReader(assetManager)
}
