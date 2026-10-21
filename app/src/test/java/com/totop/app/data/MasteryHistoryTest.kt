package com.totop.app.data

import com.totop.app.data.repo.MasteryHistory
import com.totop.app.domain.model.MasterySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 掌握度 90 天快照裁剪的 JVM 单测（文档 5.2）。 */
class MasteryHistoryTest {
    private fun snapshot(
        day: Long,
        score: Double = 50.0,
    ) = MasterySnapshot(day, score)

    @Test
    @DisplayName("超过 90 天时只保留最近 90 条，且按日期升序")
    fun trimsToKeepDays() {
        val history = (0 until 120).map { snapshot(19_000L + it, (it % 100).toDouble()) }
        val trimmed = MasteryHistory.trim(history)
        assertEquals(90, trimmed.size)
        assertEquals(19_000L + 30, trimmed.first().dateEpochDayUtc)
        assertEquals(19_000L + 119, trimmed.last().dateEpochDayUtc)
        // 第 119 条（最后一条）的分数为 119 % 100 = 19
        assertEquals(19.0, trimmed.last().score)
        assertEquals(trimmed.sortedBy { it.dateEpochDayUtc }, trimmed)
    }

    @Test
    @DisplayName("不足 90 条时原样保留")
    fun keepsShorterHistory() {
        val history = listOf(snapshot(1L), snapshot(2L), snapshot(3L))
        assertEquals(history, MasteryHistory.trim(history))
    }

    @Test
    @DisplayName("同日多条快照只保留最后一条")
    fun sameDayKeepsLast() {
        val history = listOf(snapshot(10L, 30.0), snapshot(11L, 40.0), snapshot(10L, 55.0))
        val trimmed = MasteryHistory.trim(history)
        assertEquals(2, trimmed.size)
        assertEquals(55.0, trimmed.first { it.dateEpochDayUtc == 10L }.score)
    }

    @Test
    @DisplayName("空历史返回空列表")
    fun emptyHistory() {
        assertTrue(MasteryHistory.trim(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("keepDays 非正时按 1 处理，不退化为空")
    fun nonPositiveKeepDays() {
        val history = listOf(snapshot(1L), snapshot(2L))
        // keepDays <= 0 被夹到 1：只保留最近一条，而不是清空
        assertEquals(1, MasteryHistory.trim(history, keepDays = 0).size)
        assertEquals(1, MasteryHistory.trim(history, keepDays = -5).size)
        assertEquals(2L, MasteryHistory.trim(history, keepDays = -5).single().dateEpochDayUtc)
    }

    @Test
    @DisplayName("乱序输入也会被排序")
    fun unsortedInputSorted() {
        val history = listOf(snapshot(5L), snapshot(1L), snapshot(3L))
        assertEquals(listOf(1L, 3L, 5L), MasteryHistory.trim(history).map { it.dateEpochDayUtc })
    }
}
