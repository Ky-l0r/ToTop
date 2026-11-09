package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 某场考试中某一科的成绩。
 *
 * 外键级联删除：删一次考试连带删该场全部科目。仓库层仍会在事务里先删子表，
 * 不依赖 SQLite 的 foreign_keys 开关。
 */
@Entity(
    tableName = "exam_score",
    foreignKeys = [
        ForeignKey(
            entity = ExamSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["subject"]),
        Index(value = ["sessionId", "subject"], unique = true),
    ],
)
data class ExamScoreEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** 科目代码，取值见 Subjects。 */
    val subject: String,
    val score: Double,
    val fullScore: Double,
    /** 该科的年级排名（选填）。 */
    val gradeRank: Int?,
    /** 该科的班级排名（选填）。 */
    val classRank: Int?,
)
