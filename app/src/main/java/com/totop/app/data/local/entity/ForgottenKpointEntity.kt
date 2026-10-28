package com.totop.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 陪伴模式里「问过的知识点」。
 *
 * 为什么单独一张表而不是塞进 mistake_record：
 * 这些点没有错题、没有学生步骤，只是学生在写作业时忽然想不起来的一次提问。
 * 它的用途是把「易忘」与「易错」并列呈现（首页「还需要多练」与复习资料），
 * 硬挂到错题上会污染错题统计与掌握度公式。
 *
 * 一行对应一个知识点：重复提问只累加 [askCount] 并刷新 [lastAskedAt]，
 * 因此表的大小上限就是知识点总数。
 */
@Entity(
    tableName = "forgotten_kpoint",
    indices = [Index(value = ["lastAskedAt"])],
)
data class ForgottenKpointEntity(
    /** 受控知识点 ID（`^KP_[A-Z]{2,12}_[0-9]{4}$`）。 */
    @PrimaryKey val kpointId: String,
    /** 知识点名称，读取时不必再回查目录。 */
    val name: String,
    /** 学科代码，用于按科目筛选复习资料。 */
    val subject: String,
    /** 累计被问次数。 */
    val askCount: Int,
    /** 最近一次提问时间，UTC 毫秒。 */
    val lastAskedAt: Long,
)
