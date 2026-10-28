package com.totop.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import com.totop.app.data.local.entity.AiTraceEntity
import com.totop.app.data.local.entity.CacheEntryEntity
import com.totop.app.data.local.entity.DailyStatsEntity
import com.totop.app.data.local.entity.ExerciseEntity
import com.totop.app.data.local.entity.ForgottenKpointEntity
import com.totop.app.data.local.entity.HintLogEntity
import com.totop.app.data.local.entity.KnowledgePointEntity
import com.totop.app.data.local.entity.KpointEdgeEntity
import com.totop.app.data.local.entity.MasteryEntity
import com.totop.app.data.local.entity.MistakeKpointEntity
import com.totop.app.data.local.entity.MistakeRecordEntity
import com.totop.app.data.local.entity.OutboxEntity
import com.totop.app.data.local.entity.QuestionEntity
import com.totop.app.data.local.entity.ReportEntity
import com.totop.app.data.local.entity.ReviewLogEntity
import com.totop.app.data.local.entity.SettingsEntity

/**
 * To Top 本地数据库（开发方案第五章，15 张表全部登记在 [entities]）。
 *
 * 版本策略：
 * - version = 1 为 M2 首发版本；
 * - exportSchema = true，schema JSON 输出到 app/schemas（见 app/build.gradle.kts 的 room.schemaLocation），
 *   迁移测试通过 MigrationTestHelper 读取该目录；
 * - 后续任何表结构变更都必须：升 version、补 Migration、在 app/schemas 提交新版本 JSON、
 *   在 MigrationTest 中补一条「旧版本 -> 新版本」用例。
 *
 * 所有时间列统一为 UTC 毫秒 Long（日期类为 UTC 日序号 Long），展示层再转本地时区（文档 5.2）。
 */
@Database(
    entities = [
        QuestionEntity::class,
        MistakeRecordEntity::class,
        KnowledgePointEntity::class,
        KpointEdgeEntity::class,
        MistakeKpointEntity::class,
        ReviewLogEntity::class,
        HintLogEntity::class,
        ExerciseEntity::class,
        MasteryEntity::class,
        DailyStatsEntity::class,
        ReportEntity::class,
        AiTraceEntity::class,
        SettingsEntity::class,
        OutboxEntity::class,
        CacheEntryEntity::class,
        ForgottenKpointEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao

    abstract fun mistakeDao(): MistakeDao

    abstract fun knowledgeDao(): KnowledgeDao

    abstract fun reviewDao(): ReviewDao

    abstract fun hintDao(): HintDao

    abstract fun exerciseDao(): ExerciseDao

    abstract fun masteryDao(): MasteryDao

    abstract fun dailyStatsDao(): DailyStatsDao

    abstract fun reportDao(): ReportDao

    abstract fun aiTraceDao(): AiTraceDao

    abstract fun settingsDao(): SettingsDao

    abstract fun outboxDao(): OutboxDao

    abstract fun cacheEntryDao(): CacheEntryDao

    abstract fun forgottenKpointDao(): ForgottenKpointDao

    /** 表名常量：备份、导出与测试按名称引用，避免散落的字符串字面量。 */
    object Tables {
        const val QUESTION = "question"
        const val MISTAKE_RECORD = "mistake_record"
        const val KNOWLEDGE_POINT = "knowledge_point"
        const val KPOINT_EDGE = "kpoint_edge"
        const val MISTAKE_KPOINT = "mistake_kpoint"
        const val REVIEW_LOG = "review_log"
        const val HINT_LOG = "hint_log"
        const val EXERCISE = "exercise"
        const val MASTERY = "mastery"
        const val DAILY_STATS = "daily_stats"
        const val REPORT = "report"
        const val AI_TRACE = "ai_trace"
        const val SETTINGS = "settings"
        const val OUTBOX = "outbox"
        const val CACHE_ENTRY = "cache_entry"
        const val FORGOTTEN_KPOINT = "forgotten_kpoint"

        /** 全部业务表，备份与清库按此顺序处理。 */
        val ALL: List<String> =
            listOf(
                QUESTION,
                MISTAKE_RECORD,
                KNOWLEDGE_POINT,
                KPOINT_EDGE,
                MISTAKE_KPOINT,
                REVIEW_LOG,
                HINT_LOG,
                EXERCISE,
                MASTERY,
                DAILY_STATS,
                REPORT,
                AI_TRACE,
                SETTINGS,
                OUTBOX,
                CACHE_ENTRY,
                FORGOTTEN_KPOINT,
            )
    }

    companion object {
        /** 当前数据库版本。 */
        const val VERSION: Int = 3

        /** 数据库文件名。 */
        const val NAME: String = "totop.db"
    }
}
