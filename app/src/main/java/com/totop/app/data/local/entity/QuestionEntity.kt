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
    /**
     * 学生手动标记「已掌握」的时间，UTC 毫秒；null 表示没标过。
     *
     * 为什么不写 mastery 表：掌握度只能由复习自评、错题频次、覆盖度推导，
     * 学生自评直接改分数会让推荐路径失真。这个字段只回答「这道题我确认会了」，
     * 口径见 docs/需求定稿-0.5.0.md 第一节。
     */
    val selfMasteredAt: Long? = null,
    /**
     * 这道题的全部页面图片，JSON 字符串数组，顺序即页序。
     *
     * [imagePath] 仍是第 1 页（兼容旧记录与缓存锚点）；多页题目靠这个字段
     * 在错题详情里翻页。旧数据为空数组，读取时回退到 [imagePath]。
     */
    val imagePathsJson: String = "[]",
)
