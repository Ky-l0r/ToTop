package com.totop.app.data.local.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.AiTraceDao
import com.totop.app.data.local.dao.CacheEntryDao
import com.totop.app.data.local.dao.DailyStatsDao
import com.totop.app.data.local.dao.ExerciseDao
import com.totop.app.data.local.dao.ForgottenKpointDao
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
    /**
     * v1 -> v2：题目表加「已掌握」标记与多页图片列表。
     *
     * 两个字段都带默认值，旧记录不需要回填：selfMasteredAt 为 null 表示没标过，
     * imagePathsJson 为空数组时读取侧回退到 imagePath（第 1 页）。
     */
    private val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE question ADD COLUMN selfMasteredAt INTEGER")
                db.execSQL("ALTER TABLE question ADD COLUMN imagePathsJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

    /**
     * v2 -> v3：新增「问过的知识点」表。
     *
     * 专注时长没有新建表，而是累加到 daily_stats 的 studySeconds（保留学科键 _focus），
     * 详见 CompanionInsightRepository。
     */
    private val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS forgotten_kpoint (" +
                        "kpointId TEXT NOT NULL, name TEXT NOT NULL, subject TEXT NOT NULL, " +
                        "askCount INTEGER NOT NULL, lastAskedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(kpointId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_forgotten_kpoint_lastAskedAt " +
                        "ON forgotten_kpoint(lastAskedAt)",
                )
            }
        }

    /** 迁移列表。追加新版本时保持升序。 */
    val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

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
    fun provideForgottenKpointDao(database: AppDatabase): ForgottenKpointDao = database.forgottenKpointDao()

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
