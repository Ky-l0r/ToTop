package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 开发方案 5.1 `report` 表：周报 / 月报。 */
@Entity(
    tableName = "report",
    indices = [
        Index(value = ["type", "periodKey"], unique = true),
        Index(value = ["createdAt"]),
    ],
)
data class ReportEntity(
    @PrimaryKey val id: String,
    /** weekly / monthly。 */
    val type: String,
    /** 周期键，例如 2025-W03 或 2025-01。 */
    val periodKey: String,
    /** 统计数据，JSON 对象。 */
    val statsJson: String,
    /** AI 生成的点评；未开启时为 null。 */
    val aiComment: String?,
    /** 创建时间，UTC 毫秒。 */
    val createdAt: Long,
)
