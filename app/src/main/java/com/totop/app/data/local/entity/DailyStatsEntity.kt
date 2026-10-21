package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 开发方案 5.1 `daily_stats` 表：每日汇总。
 *
 * 主键 (date, subject)；DailyStatsWorker 使用 UPSERT（文档 5.2）。
 * date 为 UTC 日序号（自 1970-01-01 起的天数），与时区无关。
 */
@Entity(
    tableName = "daily_stats",
    primaryKeys = ["date", "subject"],
    indices = [Index(value = ["date"])],
)
data class DailyStatsEntity(
    val date: Long,
    val subject: String,
    val mistakeCount: Int,
    val kpointCount: Int,
    val hintCount: Int,
    val reviewDoneCount: Int,
    val studySeconds: Long,
)
