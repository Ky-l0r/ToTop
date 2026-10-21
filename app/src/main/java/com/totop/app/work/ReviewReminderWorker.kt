package com.totop.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.totop.app.MainActivity
import com.totop.app.R
import com.totop.app.data.local.dao.MistakeDao
import com.totop.app.data.local.dao.ReviewDao
import com.totop.app.data.repo.UtcDay
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 复习提醒 Worker（开发方案 FR-4.4：简化 SM-2 排期 + 本地通知）。
 *
 * 完整流程：
 * 1. 取出全部错题（上限 [MAX_MISTAKES] 条，避免低端机上全表扫描）；
 * 2. 用 [ReviewSchedule.replay] 从 review_log 重放出每条错题的 SM-2 状态；
 * 3. 用 [ReviewSchedule.dueToday] 选出今天该提醒的题（最多 [ReviewSchedule.DEFAULT_DAILY_LIMIT] 条）；
 * 4. 有到期题才发通知；无到期题不发，避免打扰。
 *
 * 合规与体验：
 * - **Android 13+ 需要 POST_NOTIFICATIONS 运行时授权**；未授权时静默返回 success，
 *   不弹任何东西、不报错（用户可能只是不想被打扰）；
 * - 通知点击直达复习页（查看任务主入口）；
 * - 同一时间只有一条通知（固定 id），重复执行只会更新内容，不会堆叠。
 */
@HiltWorker
class ReviewReminderWorker
    @AssistedInject
    constructor(
        @Assisted applicationContext: Context,
        @Assisted params: WorkerParameters,
        private val mistakeDao: MistakeDao,
        private val reviewDao: ReviewDao,
    ) : CoroutineWorker(applicationContext, params) {
        override suspend fun doWork(): Result {
            if (!canNotify()) return Result.success()
            return runCatching {
                val nowUtc = System.currentTimeMillis()
                val today = UtcDay.of(nowUtc)
                val items = buildItems()
                val due = ReviewSchedule.dueToday(items, nowUtc)
                if (due.isEmpty()) return Result.success()
                notify(due.size)
                // 记录本次提醒的日期，便于排查「为什么今天没提醒」
                applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_REMINDED_DAY, today)
                    .apply()
                Result.success()
            }.getOrElse { Result.retry() }
        }

        /**
         * 从库里重建每条错题的排期。
         *
         * 只取「最近还有效」的错题：全部错题都参与会让低端机卡顿，
         * 这里按创建时间倒序取前 [MAX_MISTAKES] 条。
         */
        private suspend fun buildItems(): List<ReviewSchedule.ReviewItem> {
            val mistakes = runCatching { mistakeDao.page(MAX_MISTAKES, 0) }.getOrDefault(emptyList())
            return mistakes.mapNotNull { record ->
                val logs =
                    runCatching { reviewDao.byMistakeId(record.id) }.getOrDefault(emptyList())
                        .sortedBy { it.reviewedAt }
                if (logs.isEmpty()) {
                    // 从未复习过：从错题创建那一刻起就到期，直接进提醒队列
                    ReviewSchedule.ReviewItem(
                        mistakeId = record.id,
                        kpointId = kpointOf(record.id),
                        dueAtUtc = record.createdAt,
                        repetition = 0,
                        intervalDays = 0,
                    )
                } else {
                    val state =
                        ReviewSchedule.replay(
                            reviewedAtUtcList = logs.map { it.reviewedAt },
                            gotItList = logs.map { it.selfResult == SELF_DONE },
                        )
                    ReviewSchedule.ReviewItem(
                        mistakeId = record.id,
                        kpointId = kpointOf(record.id),
                        dueAtUtc = state.dueAtUtc,
                        repetition = state.repetition,
                        intervalDays = state.intervalDays,
                    )
                }
            }
        }

        /** 取该错题的第一个知识点 id（通知文案与统计用）；没有则空串。 */
        private suspend fun kpointOf(mistakeId: String): String =
            runCatching { mistakeDao.kpointsOfMistake(mistakeId).firstOrNull()?.kpointId ?: "" }.getOrDefault("")

        /** Android 13+ 需要运行时通知权限；低版本默认允许。 */
        private fun canNotify(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun notify(count: Int) {
            val manager =
                applicationContext.getSystemService(
                    Context.NOTIFICATION_SERVICE,
                ) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        applicationContext.getString(R.string.notification_channel_review),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = applicationContext.getString(R.string.notification_channel_review_desc)
                        setShowBadge(true)
                    }
                manager.createNotificationChannel(channel)
            }
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pending =
                PendingIntent.getActivity(
                    applicationContext,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(applicationContext.getString(R.string.notification_review_title))
                    .setContentText(ReviewSchedule.notificationText(count))
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification) }
        }

        companion object {
            /** 通知渠道 id。 */
            const val CHANNEL_ID: String = "totop-review-reminder"

            /** 固定通知 id：重复执行只更新不堆叠。 */
            const val NOTIFICATION_ID: Int = 1001

            /** PendingIntent 请求码。 */
            const val REQUEST_CODE: Int = 2001

            /** 单次最多扫描多少道错题（低端机保护）。 */
            const val MAX_MISTAKES: Int = 200

            /** 自评「做对了」的 wire 值。 */
            const val SELF_DONE: String = "got_it"

            /** 偏好文件名与键（排查用）。 */
            const val PREFS: String = "totop_worker_state"

            const val KEY_LAST_REMINDED_DAY: String = "review.lastRemindedDay"
        }
    }
