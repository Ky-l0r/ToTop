package com.totop.app.data.local.di

import android.content.Context
import androidx.room.Room
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.AiTraceDao
import com.totop.app.data.local.dao.CacheEntryDao
import com.totop.app.data.local.dao.DailyStatsDao
import com.totop.app.data.local.dao.ExerciseDao
import com.totop.app.data.local.dao.HintDao
import com.totop.app.data.local.dao.KnowledgeDao
import com.totop.app.data.local.dao.MasteryDao
import com.totop.app.data.local.dao.MistakeDao
import com.totop.app.data.local.dao.OutboxDao
import com.totop.app.data.local.dao.QuestionDao
import com.totop.app.data.local.dao.ReportDao
import com.totop.app.data.local.dao.ReviewDao
import com.totop.app.data.local.dao.SettingsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 依赖注入（开发方案 2.3：data/local）。
 *
 * 约定：
 * 1. 数据库为进程内单例；
 * 2. 不开启 fallbackToDestructiveMigration：表结构变更必须写迁移，避免用户错题被静默清空；
 * 3. 当前 version = 1 无迁移，后续版本在 [MIGRATIONS] 中追加。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** 迁移列表。version 1 为首发版本，暂无迁移；后续版本在此追加 Migration。 */
    val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .apply {
                if (MIGRATIONS.isNotEmpty()) {
                    // Room 的 addMigrations 只提供可变参数签名；
                    // 此处在构建数据库时执行一次，数组复制开销可忽略（非热路径）
                    @Suppress("SpreadOperator")
                    addMigrations(*MIGRATIONS)
                }
            }
            .build()

    @Provides
    fun provideQuestionDao(database: AppDatabase): QuestionDao = database.questionDao()

    @Provides
    fun provideMistakeDao(database: AppDatabase): MistakeDao = database.mistakeDao()

    @Provides
    fun provideKnowledgeDao(database: AppDatabase): KnowledgeDao = database.knowledgeDao()

    @Provides
    fun provideReviewDao(database: AppDatabase): ReviewDao = database.reviewDao()

    @Provides
    fun provideHintDao(database: AppDatabase): HintDao = database.hintDao()

    @Provides
    fun provideExerciseDao(database: AppDatabase): ExerciseDao = database.exerciseDao()

    @Provides
    fun provideMasteryDao(database: AppDatabase): MasteryDao = database.masteryDao()

    @Provides
    fun provideDailyStatsDao(database: AppDatabase): DailyStatsDao = database.dailyStatsDao()

    @Provides
    fun provideReportDao(database: AppDatabase): ReportDao = database.reportDao()

    @Provides
    fun provideAiTraceDao(database: AppDatabase): AiTraceDao = database.aiTraceDao()

    @Provides
    fun provideSettingsDao(database: AppDatabase): SettingsDao = database.settingsDao()

    @Provides
    fun provideOutboxDao(database: AppDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideCacheEntryDao(database: AppDatabase): CacheEntryDao = database.cacheEntryDao()
}
