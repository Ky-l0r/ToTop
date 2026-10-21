package com.totop.app.ui.knowledge

import com.totop.app.domain.model.ErrorType
import com.totop.app.domain.model.KnowledgePoint
import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasteryBand
import com.totop.app.domain.model.MasterySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 掌握度图表数据装配（FR-3.1 / FR-3.4）。
 *
 * 硬约束：
 * - 色阶阈值必须与 domain 的 Mastery.band 完全一致（红 <40 / 黄 40-70 / 绿 >70）；
 * - 雷达最多 8 个轴且按错题数降序；
 * - 趋势降采样后点数 **一定不超过** 60；
 * - 饼图角度总和 360 度，未出现的错因不画扇区。
 */
class KnowledgeChartsTest {
    @Test
    @DisplayName("色阶阈值与 Mastery 一致")
    fun bandThresholds() {
        assertEquals(MasteryBand.RED, KnowledgeCharts.bandOfScore(0.0))
        assertEquals(MasteryBand.RED, KnowledgeCharts.bandOfScore(39.99))
        assertEquals(MasteryBand.YELLOW, KnowledgeCharts.bandOfScore(40.0))
        assertEquals(MasteryBand.YELLOW, KnowledgeCharts.bandOfScore(70.0))
        assertEquals(MasteryBand.GREEN, KnowledgeCharts.bandOfScore(70.01))
        assertEquals(MasteryBand.GREEN, KnowledgeCharts.bandOfScore(100.0))

        // 与领域模型逐点比对，防止两套阈值漂移
        listOf(0.0, 39.99, 40.0, 55.5, 70.0, 70.01, 100.0).forEach { score ->
            assertEquals(
                Mastery(kpointId = "K", score = score, updatedAtUtc = 0L).band,
                KnowledgeCharts.bandOfScore(score),
            )
        }
    }

    @Test
    @DisplayName("雷达：按错题数降序，最多 8 个轴")
    fun radarOrderingAndLimit() {
        val masteries =
            (1..10).map { index ->
                Mastery(
                    kpointId = "KP_MATH_000" + index,
                    score = 30.0 + index,
                    history = emptyList(),
                    updatedAtUtc = 0L,
                )
            }.associateBy { it.kpointId }
        val names = masteries.keys.associateWith { "知识点" + it.takeLast(1) }
        val mistakes = masteries.keys.mapIndexed { index, id -> id to (index + 1) }.toMap()

        val radar = KnowledgeCharts.radar(masteries, names, mistakes)
        assertEquals(RadarData.MAX_AXES, radar.axes.size)
        // 错题最多的 KP_MATH_00010（10 次）排第一
        assertEquals("KP_MATH_00010", radar.axes.first().kpointId)
        assertTrue(radar.axes.zipWithNext().all { (a, b) -> a.mistakeCount >= b.mistakeCount })
        assertTrue(radar.drawable)
    }

    @Test
    @DisplayName("雷达：少于 3 个轴时不可绘制，界面改画条形")
    fun radarFallback() {
        val masteries =
            listOf(
                Mastery(kpointId = "KP_MATH_0001", score = 50.0, updatedAtUtc = 0L),
                Mastery(kpointId = "KP_MATH_0002", score = 60.0, updatedAtUtc = 0L),
            ).associateBy { it.kpointId }
        val radar = KnowledgeCharts.radar(masteries, emptyMap(), emptyMap())
        assertEquals(2, radar.axes.size)
        assertFalse(radar.drawable)
    }

    @Test
    @DisplayName("热力：没有掌握度记录的知识点标记为待练习而不是 0 分")
    fun heatRowsUntouched() {
        val points =
            listOf(
                KnowledgePoint(id = "KP_MATH_0000", name = "函数", subject = "math", parentId = null, chapter = "函数"),
                KnowledgePoint(
                    id = "KP_MATH_0001",
                    name = "单调性",
                    subject = "math",
                    parentId = "KP_MATH_0000",
                    chapter = "函数",
                ),
                KnowledgePoint(
                    id = "KP_MATH_0002",
                    name = "奇偶性",
                    subject = "math",
                    parentId = "KP_MATH_0000",
                    chapter = "函数",
                ),
            )
        val masteries =
            mapOf(
                "KP_MATH_0001" to Mastery(kpointId = "KP_MATH_0001", score = 25.0, updatedAtUtc = 0L),
            )
        val rows = KnowledgeCharts.heatRows(points, masteries, mapOf("KP_MATH_0001" to 3))
        // 根节点（章节）不进入叶子列表
        assertEquals(2, rows.size)
        val untouched = rows.first { it.kpointId == "KP_MATH_0002" }
        assertTrue(untouched.untouched)
        assertEquals("待练习", untouched.scoreText)
        assertEquals(MasteryBand.RED, rows.first { it.kpointId == "KP_MATH_0001" }.band)
    }

    @Test
    @DisplayName("趋势：降采样后点数不超过 60，且保留首尾趋势")
    fun trendDownsampleBounded() {
        val history =
            (0 until 365).map { day ->
                MasterySnapshot(dateEpochDayUtc = day.toLong(), score = 40.0 + (day % 20))
            }
        val trend = KnowledgeCharts.trend(history)
        assertTrue(trend.points.size <= KnowledgeCharts.MAX_TREND_POINTS)
        assertEquals(365, trend.rawCount)
        assertTrue(trend.downsampled)
        assertTrue(trend.note.contains("降采样"))
    }

    @Test
    @DisplayName("趋势：点数少时不降采样")
    fun trendNoDownsampleWhenSmall() {
        val history = (0 until 10).map { MasterySnapshot(it.toLong(), 50.0 + it) }
        val trend = KnowledgeCharts.trend(history)
        assertEquals(10, trend.points.size)
        assertFalse(trend.downsampled)
        assertEquals(50.0, trend.minValue)
        assertEquals(59.0, trend.maxValue)
    }

    @Test
    @DisplayName("趋势：空历史不崩溃")
    fun trendEmpty() {
        val trend = KnowledgeCharts.trend(emptyList())
        assertTrue(trend.points.isEmpty())
        assertEquals(0, trend.rawCount)
    }

    @Test
    @DisplayName("合并历史：同日取均值，跨知识点按日归并")
    fun mergeHistoryByDay() {
        val a =
            Mastery(
                kpointId = "A",
                score = 50.0,
                history = listOf(MasterySnapshot(1L, 40.0), MasterySnapshot(2L, 60.0)),
                updatedAtUtc = 0L,
            )
        val b =
            Mastery(
                kpointId = "B",
                score = 50.0,
                history = listOf(MasterySnapshot(1L, 60.0)),
                updatedAtUtc = 0L,
            )
        val merged = KnowledgeCharts.mergeHistory(listOf(a, b))
        assertEquals(2, merged.size)
        assertEquals(50.0, merged.first().score)
        assertEquals(60.0, merged.last().score)
    }

    @Test
    @DisplayName("饼图：角度总和 360 度，未出现的错因不画扇区")
    fun pieAnglesAndFiltering() {
        val distribution =
            mapOf(
                ErrorType.CALCULATION_ERROR.code to 3,
                ErrorType.CONCEPT_CONFUSION.code to 1,
            )
        val pie = KnowledgeCharts.pie(distribution)
        assertEquals(4, pie.total)
        assertEquals(2, pie.slices.size)
        assertFalse(pie.isEmpty)
        val sweepSum = pie.slices.sumOf { it.sweepAngle }
        assertEquals(360.0, sweepSum, 1e-9)
        // 扇区顺序固定跟随 ErrorType.FIXED：概念混淆（1 次）在计算失误（3 次）之前
        assertEquals(0.0, pie.slices.first().startAngle, 1e-9)
        assertEquals(ErrorType.CONCEPT_CONFUSION.label, pie.slices.first().label)
        assertEquals(90.0, pie.slices[1].startAngle, 1e-9)
        assertEquals(ErrorType.CALCULATION_ERROR.label, pie.slices[1].label)
        assertEquals("75%", pie.slices[1].percentText)
    }

    @Test
    @DisplayName("饼图：没有任何记录时为空")
    fun pieEmpty() {
        assertTrue(KnowledgeCharts.pie(emptyMap()).isEmpty)
        assertTrue(KnowledgeCharts.pie(mapOf(ErrorType.MISSING_STEPS.code to 0)).isEmpty)
    }

    @Test
    @DisplayName("雷达顶点：第 0 个轴指向正上方，且半径随归一化分数缩放")
    fun radarVerticesGeometry() {
        val vertices =
            KnowledgeCharts.radarVertices(
                count = 4,
                radius = 100f,
                centerX = 100f,
                centerY = 100f,
                scale = { 1.0 },
            )
        assertEquals(4, vertices.size)
        // 正上方：x 不变，y 减小
        assertEquals(100f, vertices.first().first, 0.01f)
        assertEquals(0f, vertices.first().second, 0.01f)

        val half = KnowledgeCharts.radarVertices(4, 100f, 100f, 100f) { 0.5 }
        assertEquals(50f, half.first().second, 0.01f)
    }

    @Test
    @DisplayName("折线顶点：纵轴自适应，值越大坐标越靠上")
    fun lineVerticesGeometry() {
        val trend =
            KnowledgeCharts.trend(
                listOf(MasterySnapshot(1L, 20.0), MasterySnapshot(2L, 80.0)),
            )
        val vertices = KnowledgeCharts.lineVertices(trend, width = 100f, height = 100f)
        assertEquals(2, vertices.size)
        assertTrue(vertices.first().second > vertices.last().second)
        assertEquals(0f, vertices.first().first, 0.01f)
        assertEquals(100f, vertices.last().first, 0.01f)
    }
}
