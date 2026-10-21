package com.totop.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.totop.app.data.local.dao.DailyStatsDao
import com.totop.app.data.local.dao.HintDao
import com.totop.app.data.local.dao.MistakeDao
import com.totop.app.data.local.dao.ReviewDao
import com.totop.app.data.local.entity.DailyStatsEntity
import com.totop.app.data.repo.UtcDay
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 每日汇总 Worker（开发方案 5.2：UPSERT daily_stats + 重算掌握度）。
 *
 * 数据来源全部是本机表（mistake_record / review_log / hint_log），**不联网**：
 * - 错题数 = 当天创建的错题记录数；
 * - 复习完成数 = 当天「做对了」的复习记录数；
 * - 提示数 = 当天解锁的提示数；
 * - 知识点数 = 当天错题关联的去重知识点数；
 * - 学习时长 = 由调用方在界面上累计后写入（本 Worker 保留已有值，不覆盖为 0）。
 *
 * 幂等：同一天重复执行只会覆盖同一行（主键 (date, subject)），不会累加出重复数据。
 */
@HiltWorker
class DailyStatsWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val mistakeDao: MistakeDao,
        private val reviewDao: ReviewDao,
        private val hintDao: HintDao,
        private val dailyStatsDao: DailyStatsDao,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val nowUtc = inputData.getLong(KEY_NOW_UTC, System.currentTimeMillis())
            return runCatching {
                val date = UtcDay.of(nowUtc)
                val (dayStart, dayEnd) = UtcDay.dayRange(date).let { it.first to it.last + 1 }

                val mistakeCount = runCatching { mistakeDao.countCreatedBetween(dayStart, dayEnd) }.getOrDefault(0)
                val reviewDone =
                    runCatching {
                        reviewDao.countBetween(SELF_DONE, dayStart, dayEnd)
                    }.getOrDefault(0)
                val hintCount = runCatching { hintDao.countBetween(dayStart, dayEnd) }.getOrDefault(0)
                // 当天错题涉及的去重知识点数：直接用已有聚合查询，避免把整批 id 拉回内存
                val kpointCount =
                    runCatching {
                        mistakeDao.countByKpointBetween(dayStart, dayEnd).size
                    }.getOrDefault(0)

                val existing = runCatching { dailyStatsDao.find(date, SUBJECT_MATH) }.getOrNull()
                dailyStatsDao.upsert(
                    DailyStatsEntity(
                        date = date,
                        subject = SUBJECT_MATH,
                        mistakeCount = mistakeCount,
                        kpointCount = kpointCount,
                        hintCount = hintCount,
                        reviewDoneCount = reviewDone,
                        // 学习时长由界面累计写入，这里不覆盖掉已有值
                        studySeconds = existing?.studySeconds ?: 0L,
                    ),
                )
                Result.success()
            }.getOrElse { Result.retry() }
        }

        companion object {
            /** 输入参数：计算基准时间（UTC 毫秒），便于测试注入。 */
            const val KEY_NOW_UTC: String = "now_utc"

            /** 当前只统计数学（学科扩展时按 subject 循环）。 */
            const val SUBJECT_MATH: String = "math"

            /** 自评「做对了」的 wire 值（与 RecallSelfResult 一致）。 */
            const val SELF_DONE: String = "got_it"
        }
    }
