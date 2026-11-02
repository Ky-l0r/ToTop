package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 学生自己攒进来的「资料」：一道题的解析或一份别人的解法。
 *
 * 为什么单开一张表而不是并进错题本：这些条目**没有做错这回事**——
 * 它们可能来自作业帮、老师发的解析、同学的笔记，学生只是想让 AI 学会这种讲法。
 * 混进 mistake_record 会污染错题统计与掌握度分母（那两件事只认学生自己做过的题）。
 *
 * 检索口径：按科目召回，按时间倒序，因此 [subject] 与 [createdAtUtc] 各建索引。
 */
@Entity(
    tableName = "learned_material",
    indices = [Index(value = ["subject"]), Index(value = ["createdAtUtc"])],
)
data class LearnedMaterialEntity(
    @PrimaryKey val id: String,
    /** 受控科目代码；解析不出科目时落 `general`（与题目同一套口径）。 */
    val subject: String,
    /** 能映射到内置知识点时填受控 ID，否则为空。 */
    val kpointId: String?,
    /** 知识点名或题型名，检索与列表展示都用它。 */
    val kpointName: String,
    /** 一句话标题，列表里显示。 */
    val title: String,
    /** 解法主线（方法名或一句话思路）。 */
    val method: String,
    /** 关键步骤，JSON 字符串数组。 */
    val keyStepsJson: String,
    /** 结论/答案。 */
    val answer: String,
    /** 易错提醒，JSON 字符串数组。 */
    val pitfallsJson: String,
    /** 来源标注，例如「作业帮截图」「老师发的解析」；学生可自己改。 */
    val sourceLabel: String,
    /** 原始截图路径，便于回看；没有图时为空。 */
    val imagePath: String?,
    /** 收录时间，UTC 毫秒。 */
    val createdAtUtc: Long,
)
