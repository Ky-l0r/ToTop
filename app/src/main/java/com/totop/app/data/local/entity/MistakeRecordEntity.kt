package com.totop.app.data.local.entity

import androidx.room.ColumnInfo
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
    /**
     * [com.totop.app.domain.model.MistakeStatus] 的 wire 值。
     *
     * default 必须与迁移里的 DEFAULT 逐字一致：Room 会把 schema 的默认值一起比对，
     * 只在迁移里写 DEFAULT 而实体不声明，升级时会抛「Migration didn't properly handle」。
     */
    @ColumnInfo(defaultValue = "wrong")
    val status: String = "wrong",
    /** 这条记录的来源：solve（拍题）/ check（核对答案）/ manual（手动标记）。 */
    @ColumnInfo(defaultValue = "solve")
    val source: String = "solve",
    /**
     * 当前这份标准解答是怎么来的（0.9 v11 新增）：
     * `ai`（首次解答）/ `import`（导入标准答案）/ `check`（核对）/ `followup`（追问订正）。
     *
     * 只加来源与时间两个字段，**没有另建 standardSolution 列**：
     * 完整过程已经在 [correctPathJson] 里，再加一列等于同一个东西存两份，
     * 一旦只更新其中一份就会出现「列表显示新答案、详情显示旧过程」。
     */
    @ColumnInfo(defaultValue = "ai")
    val solutionSource: String = "ai",
    /** 最近一次写入标准解答的时间（UTC 毫秒）；null 表示从未复写过。 */
    val solutionUpdatedAt: Long? = null,
)
