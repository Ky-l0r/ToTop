package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 开发方案 5.1 `question` 表：题目。
 *
 * 字段说明：
 * - 文档列出的最小字段集全部保留（id/subject/grade/stemText/optionsJson/hasFigure/imagePath/imageSha256/sourceType/createdAt）；
 * - 额外补充 knownJson 与 ask：领域模型 Question 需要已知条件与所求，文档未列但不可省，见报告的表结构对照表；
 * - 所有时间戳为 UTC 毫秒 Long（文档 5.2）。
 */
@Entity(
    tableName = "question",
    indices = [
        Index(value = ["imageSha256"], unique = true),
        Index(value = ["subject"]),
        Index(value = ["createdAt"]),
    ],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val grade: String,
    val stemText: String,
    /** 已知条件，JSON 字符串数组。 */
    val knownJson: String,
    /** 所求。 */
    val ask: String,
    /** 选项，JSON 字符串数组；非选择题为空数组。 */
    val optionsJson: String,
    val hasFigure: Boolean,
    val imagePath: String?,
    /** 原图 sha256，唯一索引，缓存与去重锚点（文档 5.2）。 */
    val imageSha256: String?,
    /** [com.totop.app.domain.model.QuestionSourceType] 的 wire 值。 */
    val sourceType: String,
    /** 创建时间，UTC 毫秒。 */
    val createdAt: Long,
)
