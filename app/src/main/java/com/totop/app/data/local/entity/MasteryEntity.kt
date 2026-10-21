package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `mastery` 表：知识点掌握度。
 *
 * historyJson 保存最近 90 天快照（文档 5.2，见 MasteryHistory 的裁剪逻辑）；
 * kpointId 仅建主键与索引，不建外键（知识点树可整树重建）。
 */
@Entity(
    tableName = "mastery",
    indices = [
        Index(value = ["score"]),
        Index(value = ["updatedAt"]),
    ],
)
data class MasteryEntity(
    /** 受控知识点 ID。 */
    @PrimaryKey val kpointId: String,
    /** 掌握度 0..100。 */
    val score: Double,
    /** 历史快照，JSON 数组：[epochDayUtc, score]，最多 90 条。 */
    val historyJson: String,
    /** 最近重算时间，UTC 毫秒。 */
    val updatedAt: Long,
)
