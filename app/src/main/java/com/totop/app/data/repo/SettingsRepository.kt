package com.totop.app.data.repo

import com.totop.app.data.local.dao.SettingsDao
import com.totop.app.data.local.entity.SettingsEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 键值设置仓库（开发方案 5.1 `settings` 表）。
 *
 * 安全边界（方案 6.1）：**只存非敏感配置**。API Key 一律由 ai 模块写入
 * EncryptedSharedPreferences，禁止落到本表；本类也不提供任何「写入密钥」的入口。
 */
@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dao: SettingsDao,
    ) {
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        internal fun withDispatcher(dispatcher: CoroutineDispatcher): SettingsRepository {
            this.dispatcher = dispatcher
            return this
        }

        suspend fun get(key: String): String? = withContext(dispatcher) { dao.value(key) }

        suspend fun getBoolean(
            key: String,
            default: Boolean,
        ): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

        suspend fun getInt(
            key: String,
            default: Int,
        ): Int = get(key)?.toIntOrNull() ?: default

        suspend fun getLong(
            key: String,
            default: Long,
        ): Long = get(key)?.toLongOrNull() ?: default

        suspend fun getDouble(
            key: String,
            default: Double,
        ): Double = get(key)?.toDoubleOrNull() ?: default

        fun observe(key: String): Flow<String?> = dao.observeValue(key).flowOn(dispatcher)

        suspend fun put(
            key: String,
            value: String,
            nowUtc: Long = System.currentTimeMillis(),
        ) = withContext(dispatcher) { dao.upsert(SettingsEntity(key = key, value = value, updatedAt = nowUtc)) }

        suspend fun putBoolean(
            key: String,
            value: Boolean,
            nowUtc: Long = System.currentTimeMillis(),
        ) = put(key, value.toString(), nowUtc)

        suspend fun putInt(
            key: String,
            value: Int,
            nowUtc: Long = System.currentTimeMillis(),
        ) = put(key, value.toString(), nowUtc)

        suspend fun putLong(
            key: String,
            value: Long,
            nowUtc: Long = System.currentTimeMillis(),
        ) = put(key, value.toString(), nowUtc)

        suspend fun remove(key: String): Boolean = withContext(dispatcher) { dao.delete(key) > 0 }

        suspend fun all(): Map<String, String> = withContext(dispatcher) { dao.all().associate { it.key to it.value } }

        fun observeAll(): Flow<Map<String, String>> =
            dao.observeAll().map { rows ->
                rows.associate {
                    it.key to it.value
                }
            }.flowOn(dispatcher)

        /** 设置键常量：集中管理，避免散落的字符串字面量。 */
        object Keys {
            /** 讲解模式（TeachingMode.wire）。 */
            const val MODE: String = "mode"

            /** 是否启用 TTS。 */
            const val TTS_ENABLED: String = "ttsEnabled"

            /** TTS 语速倍率。 */
            const val TTS_SPEED: String = "ttsSpeed"

            /** 是否启用周报 AI 点评（关闭可省 token）。 */
            const val REPORT_AI_ENABLED: String = "reportAiEnabled"

            /** 本次是否生成练习题（FR-9.4 开关）。 */
            const val GENERATE_EXERCISE: String = "generateExercise"

            /** 当前提示词版本（缓存键的一部分）。 */
            const val PROMPT_VERSION: String = "promptVersion"

            /** 首次启动完成标记。 */
            const val FIRST_LAUNCH_DONE: String = "firstLaunchDone"

            /** 学生学的科目（逗号分隔的代码串，见 SubjectScope）。 */
            const val SUBJECTS: String = "subjects"

            /** 是否已经选过科目：为 false 时启动先进科目选择页。 */
            const val SUBJECTS_PICKED: String = "subjectsPicked"

            /** 种子数据全局版本号。 */
            const val SEED_VERSION: String = SettingsEntity.KEY_SEED_VERSION
        }
    }
