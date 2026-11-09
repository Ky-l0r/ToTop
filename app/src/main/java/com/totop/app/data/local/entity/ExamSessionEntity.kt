package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次考试（场次）。见 docs/学情与成绩趋势.md 第三节。
 *
 * 人数（[gradeSize] / [classSize]）整场共用，不冗余到科目：
 * 同一个班、同一个年级，整场人数当然一致。
 * 排名分级：[gradeRank] / [classRank] 是**总分**排名，每科自己的排名在 [ExamScoreEntity]。
 *
 * `occurredOn` 为 UTC 日序号，与 daily_stats.date 同口径。
 */
@Entity(
    tableName = "exam_session",
    indices = [Index(value = ["occurredOn"])],
)
data class ExamSessionEntity(
    @PrimaryKey val id: String,
    val occurredOn: Long,
    /** minor 小考 / major 大考。 */
    val kind: String,
    /** 学生自填名称；为空即没填，不存空串。 */
    val name: String?,
    val gradeSize: Int?,
    val classSize: Int?,
    val gradeRank: Int?,
    val classRank: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)
