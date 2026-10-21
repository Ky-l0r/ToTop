package com.totop.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.totop.app.data.local.dao.CacheEntryDao
import com.totop.app.data.repo.LruEvictionPlan
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * 磁盘缓存清理 Worker（开发方案 5.2 / FR-8.4：磁盘上限 100MB）。
 *
 * 清理范围与顺序（两级，先 DB 后文件）：
 * 1. **cache_entry 表**：按 [LruEvictionPlan] 的 LRU 计划删除最久未访问条目，
 *    直到总量回落到上限的 85%；
 * 2. **文件系统**：清理 `cacheDir/camera/`（拍照临时图，正常流程拍完即删，这里兜底）
 *    与 `cacheDir/backup_temp/`（备份临时目录）；
 * 3. 用户数据目录 `filesDir/images/` 中的原图**不删**：
 *    原图是错题的唯一来源，删掉等于毁数据；只有缩略图可回收，
 *    回收策略由图片模块的删除接口负责，不在本 Worker 内直接删原图。
 *
 * 全部本地动作，不联网；失败返回 retry 让系统稍后重试。
 */
@HiltWorker
class CacheCleanWorker
    @AssistedInject
    constructor(
        @Assisted applicationContext: Context,
        @Assisted params: WorkerParameters,
        private val cacheEntryDao: CacheEntryDao,
    ) : CoroutineWorker(applicationContext, params) {
        override suspend fun doWork(): Result =
            runCatching {
                cleanCacheEntries()
                cleanTempFiles()
                Result.success()
            }.getOrElse { Result.retry() }

        /** cache_entry 表按字节上限做 LRU 淘汰。 */
        private suspend fun cleanCacheEntries() {
            val totalBytes = runCatching { cacheEntryDao.totalBytes() }.getOrDefault(0L)
            if (totalBytes <= maxBytes) return
            val candidates =
                runCatching { cacheEntryDao.allByLastAccess() }.getOrDefault(emptyList())
                    .map { entry ->
                        LruEvictionPlan.Candidate(
                            sha256 = entry.sha256,
                            promptVer = entry.promptVer,
                            model = entry.model,
                            sizeBytes = entry.sizeBytes,
                        )
                    }
            val plan =
                LruEvictionPlan.plan(
                    totalBytes = totalBytes,
                    maxBytes = maxBytes,
                    targetBytes = targetBytes,
                    candidates = candidates,
                )
            plan.victims.forEach { victim ->
                runCatching { cacheEntryDao.delete(victim.sha256, victim.promptVer, victim.model) }
            }
        }

        /** 清理 cacheDir 下的临时目录（相机临时图、备份临时包）。 */
        private fun cleanTempFiles() {
            val targets =
                listOf(
                    File(applicationContext.cacheDir, CAMERA_TEMP_DIR),
                    File(applicationContext.cacheDir, BACKUP_TEMP_DIR),
                )
            for (dir in targets) {
                if (dir.exists()) {
                    // 只删临时目录内部，不删目录本身（相机模块会复用目录）
                    dir.listFiles()?.forEach { child ->
                        runCatching { child.deleteRecursively() }
                    }
                }
            }
        }

        companion object {
            /** 磁盘上限 100MB（FR-8.4）。 */
            const val MAX_BYTES: Long = CacheCleanupPlan.DEFAULT_MAX_BYTES

            /** 清理后回落目标：上限的 85%。 */
            const val TARGET_BYTES: Long = 85L * 1024L * 1024L

            /** 相机临时目录名（与 CameraCaptureController.CACHE_DIR 一致）。 */
            const val CAMERA_TEMP_DIR: String = "camera"

            /** 备份临时目录名（与 BackupManager.BACKUP_TEMP_DIR 一致）。 */
            const val BACKUP_TEMP_DIR: String = "backup_temp"

            private val maxBytes: Long get() = MAX_BYTES

            private val targetBytes: Long get() = TARGET_BYTES
        }
    }
