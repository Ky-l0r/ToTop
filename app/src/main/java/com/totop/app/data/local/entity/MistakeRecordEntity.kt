package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `mistake_record` 表：错题记录。
 *
 * 与 question 一对一（历史保留多次订正时可为多对一），删除题目时级联删除记录。
 */
@Entity(
    tableName = "mistake_record",
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["questionId"]),
        Index(value = ["createdAt"]),
        Index(value = ["errorType"]),
    ],
)
data class MistakeRecordEntity(
    @PrimaryKey val id: String,
    val questionId: String,
    /** 学生过程，JSON 数组：[idx, content, status]。 */
    val studentStepsJson: String,
    val firstErrorStepIdx: Int?,
    /** [com.totop.app.domain.model.ErrorType] 的稳定 code。 */
    val errorType: String,
    val errorExplain: String,
    /** 标准解答（正确路径），JSON 对象：steps/finalAnswer/method。 */
    val correctPathJson: String,
    val finalAnswer: String,
    /** [com.totop.app.domain.model.TeachingMode] 的 wire 值。 */
    val mode: String,
    /** 入库时间，UTC 毫秒。 */
    val createdAt: Long,
    /** 同知识点第 N 次出错（FR-4.5 复发提示），首次为 1。 */
    val recurrenceIndex: Int,
)
