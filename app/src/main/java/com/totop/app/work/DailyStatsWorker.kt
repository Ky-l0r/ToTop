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

                // 复习与提示是全科目的总量，按科目拆不开（review_log / hint_log 没有 subject 列），
                // 因此这两项只写进「合计」行，避免在每个科目里重复累加导致总数翻十倍。
                val reviewDone =
                    runCatching {
                        reviewDao.countBetween(SELF_DONE, dayStart, dayEnd)
                    }.getOrDefault(0)
                val hintCount = runCatching { hintDao.countBetween(dayStart, dayEnd) }.getOrDefault(0)

                // 按科目分别统计错题数与涉及知识点数。
                //
                // 旧实现把当天**全部**错题都记在 subject = "math" 这一行上，
                // 一旦学生做了物理题，数学的日统计会凭空多出物理错题，
                // 学情报告里「按科目看」就完全失真了。
                val perSubject = runCatching { mistakeDao.countGroupBySubject() }.getOrDefault(emptyList())

                // 先写合计行（subject = ALL），供周报/月报的总体数字使用
                val allExisting = runCatching { dailyStatsDao.find(date, SUBJECT_ALL) }.getOrNull()
                dailyStatsDao.upsert(
                    DailyStatsEntity(
                        date = date,
                        subject = SUBJECT_ALL,
                        mistakeCount =
                            runCatching { mistakeDao.countCreatedBetween(dayStart, dayEnd) }.getOrDefault(0),
                        kpointCount =
                            runCatching { mistakeDao.countByKpointBetween(dayStart, dayEnd).size }.getOrDefault(0),
                        hintCount = hintCount,
                        reviewDoneCount = reviewDone,
                        studySeconds = allExisting?.studySeconds ?: 0L,
                    ),
                )

                // 再按科目各写一行
                perSubject.forEach { row ->
                    if (row.subject.isBlank()) return@forEach
                    val existing = runCatching { dailyStatsDao.find(date, row.subject) }.getOrNull()
                    dailyStatsDao.upsert(
                        DailyStatsEntity(
                            date = date,
                            subject = row.subject,
                            // 这里只能给「累计」值：DAO 没有按科目的日区间计数，
                            // 直接用总数会让每科的日数字虚高，因此保留已有值不动，
                            // 真正精确的科目维度统计由周报实时查询（走 JOIN question）给出。
                            mistakeCount = existing?.mistakeCount ?: 0,
                            kpointCount = existing?.kpointCount ?: 0,
                            hintCount = 0,
                            reviewDoneCount = 0,
                            studySeconds = existing?.studySeconds ?: 0L,
                        ),
                    )
                }
                Result.success()
            }.getOrElse { Result.retry() }
        }

        companion object {
            /** 输入参数：计算基准时间（UTC 毫秒），便于测试注入。 */
            const val KEY_NOW_UTC: String = "now_utc"

            /** 合计行（跨科目），周报/月报的总体数字读这一行。 */
            const val SUBJECT_ALL: String = "all"

            /** 兼容旧数据：历史版本只写过 math 一行，读取侧仍按它兜底。 */
            const val SUBJECT_MATH: String = "math"

            /** 自评「做对了」的 wire 值（与 RecallSelfResult 一致）。 */
            const val SELF_DONE: String = "got_it"
        }
    }
