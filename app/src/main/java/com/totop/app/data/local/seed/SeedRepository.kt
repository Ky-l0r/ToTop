@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.totop.app.data.local.seed

import android.content.res.AssetManager
import android.util.Log
import androidx.room.withTransaction
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.local.dao.KnowledgeDao
import com.totop.app.data.local.dao.SettingsDao
import com.totop.app.data.local.entity.SettingsEntity
import com.totop.app.data.local.mapper.EntityMappers
import com.totop.app.data.local.mapper.EntityMappers.toEntity
import com.totop.app.domain.model.KpointEdge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 种子数据加载结果。
 *
 * 幂等语义：同一份资产（sha256 指纹相同）在已成功写入的情况下重复调用只会返回 [Skipped]，
 * 不会重复写库、不会重复刷 timestamp。
 */
sealed interface SeedResult {
    /** 写入成功。 */
    data class Applied(
        val subject: String,
        val pointCount: Int,
        val edgeCount: Int,
        val warnings: List<SeedWarning>,
    ) : SeedResult

    /** 已是最新版本，跳过写入。 */
    data class Skipped(val subject: String, val version: String) : SeedResult

    /** 校验失败，拒绝写入并已记录日志（FR-7.3）。 */
    data class Rejected(
        val subject: String,
        val reason: SeedRejectionReason,
        val detail: String,
        val cycle: List<String> = emptyList(),
    ) : SeedResult

    /** 资产缺失或 JSON 解析失败。 */
    data class Failed(val subject: String, val detail: String) : SeedResult
}

/**
 * 资产读取抽象：默认实现读 APK 内的 assets，测试可注入内存实现。
 */
fun interface SeedAssetReader {
    /** 读取 UTF-8 文本；资产不存在时返回 null。 */
    fun read(path: String): String?
}

/** 基于 AssetManager 的实现。 */
class AssetsSeedAssetReader(private val assets: AssetManager) : SeedAssetReader {
    override fun read(path: String): String? =
        runCatching {
            assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
}

/**
 * 内置种子数据加载（开发方案 5.3 / FR-7.3）。
 *
 * 流程：
 * 1. 读取 assets/seed 下的知识点树与边表；
 * 2. 计算内容 sha256 作为版本指纹，与 settings 中记录的版本比对，一致则跳过（首启幂等）；
 * 3. [SeedValidator] 做结构与环检测，失败则**拒绝写入**并把拒绝原因写入 settings 与日志；
 * 4. 校验通过后在单个事务内整树替换（delete + insert），并写入版本号。
 *
 * 该对象不依赖任何 UI，启动时由 AppStartup 或首次访问知识库时调用 [loadAll]。
 */
@Singleton
class SeedRepository
    @Inject
    constructor(
        private val database: AppDatabase,
        private val knowledgeDao: KnowledgeDao,
        private val settingsDao: SettingsDao,
        private val assetReader: SeedAssetReader,
    ) {
        /** 数据库与文件 IO 一律切到 IO 调度器；测试可用 [withDispatcher] 替换。 */
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        private val ioDispatcher: CoroutineDispatcher get() = dispatcher

        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            }

        /** 测试专用：替换调度器（生产代码不应调用）。 */
        internal fun withDispatcher(dispatcher: CoroutineDispatcher): SeedRepository {
            this.dispatcher = dispatcher
            return this
        }

        /**
         * 加载全部种子规格。
         *
         * @return 每个学科一条结果；调用方据此决定是否提示用户。
         */
        suspend fun loadAll(specs: List<SeedSpec> = SeedSpec.ALL): List<SeedResult> =
            withContext(ioDispatcher) { specs.map { load(it) } }

        /** 加载单个学科。 */
        suspend fun load(spec: SeedSpec): SeedResult =
            withContext(ioDispatcher) {
                val pointsRaw =
                    assetReader.read(spec.pointsAsset)
                        ?: return@withContext failed(spec, "资产缺失：" + spec.pointsAsset)
                val edgesRaw =
                    assetReader.read(spec.edgesAsset)
                        ?: return@withContext failed(spec, "资产缺失：" + spec.edgesAsset)

                val points =
                    runCatching { json.decodeFromString<List<SeedKnowledgePoint>>(pointsRaw) }.getOrElse {
                        return@withContext failed(spec, "知识点树 JSON 解析失败：" + it.message)
                    }
                val edges =
                    runCatching { json.decodeFromString<List<SeedEdge>>(edgesRaw) }.getOrElse {
                        return@withContext failed(spec, "边表 JSON 解析失败：" + it.message)
                    }

                val version = fingerprint(spec, pointsRaw, edgesRaw)
                val appliedVersion = settingsDao.value(versionKey(spec.subject))
                if (appliedVersion == version) {
                    return@withContext SeedResult.Skipped(spec.subject, version)
                }

                when (val validation = SeedValidator.validate(points, edges, spec.subject)) {
                    is SeedValidation.Invalid -> {
                        // FR-7.3：出现环必须拒绝写入并记录日志
                        Log.e(
                            TAG,
                            "种子数据被拒绝写入 subject=" + spec.subject +
                                " reason=" + validation.reason +
                                " detail=" + validation.detail,
                        )
                        runCatching {
                            settingsDao.upsert(
                                SettingsEntity(
                                    key = SettingsEntity.KEY_SEED_REJECTED,
                                    value = spec.subject + ":" + validation.reason.name,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        SeedResult.Rejected(
                            subject = spec.subject,
                            reason = validation.reason,
                            detail = validation.detail,
                            cycle = validation.cycle,
                        )
                    }

                    is SeedValidation.Valid -> {
                        val pointEntities = validation.points.map { it.toEntity() }
                        val edgeEntities = validation.edges.map { it.toEntity() }
                        database.withTransaction {
                            // 按科目替换，而不是整表清空。
                            //
                            // 旧实现无条件 deleteAllKnowledgePoints()：asset 里只放数学时看不出问题，
                            // 一旦加入物理、化学等第二科，每加载一科就把前面所有科目的知识点整表删掉，
                            // 最后库里只剩下最后一个科目——表现为「只有数学有知识点」。
                            knowledgeDao.deleteEdgesOfSubject(spec.subject)
                            knowledgeDao.deleteKnowledgePointsOfSubject(spec.subject)
                            knowledgeDao.upsertKnowledgePoints(pointEntities)
                            knowledgeDao.upsertEdges(edgeEntities)
                            val now = System.currentTimeMillis()
                            settingsDao.upsert(
                                SettingsEntity(
                                    key = versionKey(spec.subject),
                                    value = version,
                                    updatedAt = now,
                                ),
                            )
                            settingsDao.upsert(
                                SettingsEntity(
                                    key = SettingsEntity.KEY_SEED_VERSION,
                                    value = version,
                                    updatedAt = now,
                                ),
                            )
                            settingsDao.upsert(
                                SettingsEntity(
                                    key = SettingsEntity.KEY_SEED_APPLIED_AT,
                                    value = now.toString(),
                                    updatedAt = now,
                                ),
                            )
                        }
                        validation.warnings.forEach {
                            Log.w(
                                TAG,
                                "种子数据提示 subject=" + spec.subject + " " + it.code + " " + it.message,
                            )
                        }
                        Log.i(
                            TAG,
                            "种子数据写入完成 subject=" + spec.subject +
                                " points=" + pointEntities.size + " edges=" + edgeEntities.size + " version=" + version,
                        )
                        SeedResult.Applied(
                            subject = spec.subject,
                            pointCount = pointEntities.size,
                            edgeCount = edgeEntities.size,
                            warnings = validation.warnings,
                        )
                    }
                }
            }

        /** 仅做校验，不写库：供构建期检查与调试入口使用。 */
        suspend fun dryRun(spec: SeedSpec): SeedValidation =
            withContext(ioDispatcher) {
                val pointsRaw = assetReader.read(spec.pointsAsset)
                val edgesRaw = assetReader.read(spec.edgesAsset)
                if (pointsRaw == null || edgesRaw == null) {
                    return@withContext SeedValidation.Invalid(
                        reason = SeedRejectionReason.EMPTY_TREE,
                        detail = "资产缺失：" + spec.pointsAsset + " / " + spec.edgesAsset,
                    )
                }
                val points =
                    runCatching { json.decodeFromString<List<SeedKnowledgePoint>>(pointsRaw) }.getOrElse {
                        return@withContext SeedValidation.Invalid(
                            reason = SeedRejectionReason.EMPTY_TREE,
                            detail = "知识点树 JSON 解析失败：" + it.message,
                        )
                    }
                val edges =
                    runCatching { json.decodeFromString<List<SeedEdge>>(edgesRaw) }.getOrElse {
                        return@withContext SeedValidation.Invalid(
                            reason = SeedRejectionReason.EMPTY_TREE,
                            detail = "边表 JSON 解析失败：" + it.message,
                        )
                    }
                SeedValidator.validate(points, edges, spec.subject)
            }

        /** 清空全部学科的知识点树（调试与「恢复内置数据」入口使用）。 */
        suspend fun clear() =
            withContext(ioDispatcher) {
                database.withTransaction {
                    knowledgeDao.deleteAllEdges()
                    knowledgeDao.deleteAllKnowledgePoints()
                    SeedSpec.ALL.forEach { settingsDao.delete(versionKey(it.subject)) }
                }
            }

        /** 只清空某一个学科（重建该科数据时使用）。 */
        suspend fun clearSubject(subject: String) =
            withContext(ioDispatcher) {
                database.withTransaction {
                    knowledgeDao.deleteEdgesOfSubject(subject)
                    knowledgeDao.deleteKnowledgePointsOfSubject(subject)
                    settingsDao.delete(versionKey(subject))
                }
            }

        /** 全量边，供环检测与图谱使用；无法识别的边类型被丢弃，不参与路径规划。 */
        suspend fun edges(): List<KpointEdge> =
            withContext(ioDispatcher) {
                with(EntityMappers) { knowledgeDao.allEdges().mapNotNull { it.toDomainOrNull() } }
            }

        private fun failed(
            spec: SeedSpec,
            detail: String,
        ): SeedResult {
            Log.e(TAG, "种子数据加载失败 subject=" + spec.subject + " " + detail)
            return SeedResult.Failed(spec.subject, detail)
        }

        private fun versionKey(subject: String): String = "seed.version." + subject

        /** 版本指纹：资产路径 + 内容 sha256，任一资产变更即视为新版本。 */
        private fun fingerprint(
            spec: SeedSpec,
            pointsRaw: String,
            edgesRaw: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(spec.pointsAsset.toByteArray(Charsets.UTF_8))
            digest.update(pointsRaw.toByteArray(Charsets.UTF_8))
            digest.update(spec.edgesAsset.toByteArray(Charsets.UTF_8))
            digest.update(edgesRaw.toByteArray(Charsets.UTF_8))
            val bytes = digest.digest()
            val builder = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                if (value < 0x10) builder.append('0')
                builder.append(Integer.toHexString(value))
            }
            return builder.toString()
        }

        private companion object {
            const val TAG = "SeedRepository"
        }
    }
