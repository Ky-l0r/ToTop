package com.totop.app.ui.knowledge

import com.totop.app.domain.model.Mastery
import com.totop.app.domain.model.MasteryBand
import com.totop.app.domain.model.MasterySnapshot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * 掌握度图表的数据准备（FR-3.1 / FR-3.4）。
 *
 * 全部是纯函数：输入领域对象，输出绘图坐标点与图例，
 * 因此可以在 JVM 单测里直接验证「降采样 <= 60 点」「雷达最多 8 个知识点」等边界。
 *
 * 性能约定（方案 7.1）：
 * - **关闭动画**：图表不生成任何动画状态或时间轴数据，只画一帧静态图；
 * - **数据降采样**：趋势图最多 [MAX_TREND_POINTS] 个点，超出时按桶内均值压缩；
 * - **禁止实时力导向**：本页不存在任何图布局迭代算法，节点位置全部来自数据顺序。
 */

/** 雷达图上的一个轴（一个知识点）。 */
data class RadarAxis(
    val kpointId: String,
    val label: String,
    /** 0..1 的归一化掌握度（半径比例）。 */
    val normalized: Double,
    /** 原始百分制分数，用于角标文本。 */
    val score: Double,
    /** 该知识点错题数（轴排序依据：错题最多的排前面）。 */
    val mistakeCount: Int,
) {
    /** 色阶。 */
    val band: MasteryBand get() = KnowledgeCharts.bandOfScore(score)
}

/** 雷达图数据。 */
data class RadarData(val axes: List<RadarAxis>, val averageScore: Double) {
    /** 是否有可绘制数据（少于 3 个轴时不画雷达，改画条形）。 */
    val drawable: Boolean get() = axes.size >= MIN_AXES

    companion object {
        /** 雷达图最少轴数：少于 3 个点无法围成多边形。 */
        const val MIN_AXES: Int = 3

        /** 雷达图最多轴数（6-8 个知识点，产品要求）。 */
        const val MAX_AXES: Int = 8
    }
}

/** 章节热力行。 */
data class HeatRow(
    val kpointId: String,
    val name: String,
    val chapter: String,
    val score: Double,
    val mistakeCount: Int,
    /** 是否尚未产生掌握度数据（显示灰色「待练习」而不是 0 分）。 */
    val untouched: Boolean,
) {
    val band: MasteryBand get() = KnowledgeCharts.bandOfScore(score)

    /** 色阶标签。 */
    val bandLabel: String
        get() =
            when (band) {
                MasteryBand.RED -> "红（<40）"
                MasteryBand.YELLOW -> "黄（40-70）"
                MasteryBand.GREEN -> "绿（>70）"
            }

    /** 分数展示：未练习过的不显示 0 分。 */
    val scoreText: String get() = if (untouched) "待练习" else Math.round(score).toString()
}

/** 趋势图的一个点。 */
data class TrendPoint(val epochDayUtc: Long, val value: Double)

/** 趋势线数据。 */
data class TrendData(
    val points: List<TrendPoint>,
    val minValue: Double,
    val maxValue: Double,
    /** 原始点数（降采样前）。 */
    val rawCount: Int,
) {
    /** 是否做了降采样。 */
    val downsampled: Boolean get() = rawCount > points.size

    /** 说明文案。 */
    val note: String
        get() =
            if (downsampled) {
                "已按 " + points.size + " 个点降采样（原始 " + rawCount + " 个点）"
            } else {
                "共 " + points.size + " 个数据点"
            }
}

/** 饼图的一个扇区。 */
data class PieSlice(
    val label: String,
    val count: Int,
    /** 占比 0..1。 */
    val ratio: Double,
    /** 起始角（度，从 12 点方向顺时针）。 */
    val startAngle: Double,
    /** 扫过角度（度）。 */
    val sweepAngle: Double,
    /** 调色板索引。 */
    val colorIndex: Int,
) {
    /** 百分比文本。 */
    val percentText: String get() = Math.round(ratio * 100).toString() + "%"
}

/** 饼图数据。 */
data class PieData(val slices: List<PieSlice>, val total: Int) {
    val isEmpty: Boolean get() = total <= 0
}

/**
 * 掌握度图表数据装配（纯函数集合）。
 */
object KnowledgeCharts {
    /** 趋势图最多点数（降采样上限）。 */
    const val MAX_TREND_POINTS: Int = 60

    /** 雷达图最多显示的知识点数（错题最多的 6-8 个）。 */
    const val RADAR_KPOINT_LIMIT: Int = RadarData.MAX_AXES

    /** 色阶判定：红 <40 / 黄 40-70 / 绿 >70，与 Mastery.band 完全一致。 */
    fun bandOfScore(score: Double): MasteryBand =
        when {
            score < Mastery.RED_BELOW -> MasteryBand.RED
            score <= Mastery.GREEN_ABOVE -> MasteryBand.YELLOW
            else -> MasteryBand.GREEN
        }

    /**
     * 组装雷达图数据。
     *
     * 排序规则（决定「先看哪个知识点」）：
     * 1. 错题数降序（错得最多的最该被看见）；
     * 2. 错题数相同时掌握度升序（更薄弱的排前面）；
     * 3. 仍相同则按受控 ID 升序，保证结果稳定可复现。
     *
     * @param masteryById 知识点 -> 掌握度
     * @param nameById 知识点 -> 名称
     * @param mistakeCountByKpoint 知识点 -> 错题数
     */
    fun radar(
        masteryById: Map<String, Mastery>,
        nameById: Map<String, String>,
        mistakeCountByKpoint: Map<String, Int>,
        limit: Int = RADAR_KPOINT_LIMIT,
    ): RadarData {
        val boundedLimit = limit.coerceIn(RadarData.MIN_AXES, RadarData.MAX_AXES)
        val candidates =
            masteryById.values
                .filter { mastery -> (mistakeCountByKpoint[mastery.kpointId] ?: 0) > 0 || mastery.score < 100.0 }
                .sortedWith(
                    compareByDescending<Mastery> { mistakeCountByKpoint[it.kpointId] ?: 0 }
                        .thenBy { it.score }
                        .thenBy { it.kpointId },
                )
                .take(boundedLimit)
        val axes =
            candidates.map { mastery ->
                RadarAxis(
                    kpointId = mastery.kpointId,
                    label = nameById[mastery.kpointId] ?: mastery.kpointId,
                    normalized = (mastery.score / 100.0).coerceIn(0.0, 1.0),
                    score = mastery.score,
                    mistakeCount = mistakeCountByKpoint[mastery.kpointId] ?: 0,
                )
            }
        val average = if (axes.isEmpty()) 0.0 else axes.sumOf { it.score } / axes.size
        return RadarData(axes = axes, averageScore = average)
    }

    /**
     * 章节热力列表。
     *
     * 输入为知识点树与掌握度；没有掌握度记录的知识点标记为 [HeatRow.untouched]，
     * 界面上显示「待练习」而不是「0 分」——避免把「没做过」误读成「很差」。
     */
    fun heatRows(
        points: List<com.totop.app.domain.model.KnowledgePoint>,
        masteryById: Map<String, Mastery>,
        mistakeCountByKpoint: Map<String, Int>,
        includeUntouched: Boolean = true,
    ): List<HeatRow> =
        points
            .filter { it.parentId != null }
            .map { point ->
                val mastery = masteryById[point.id]
                HeatRow(
                    kpointId = point.id,
                    name = point.name,
                    chapter = point.chapter,
                    score = mastery?.score ?: 0.0,
                    mistakeCount = mistakeCountByKpoint[point.id] ?: 0,
                    untouched = mastery == null,
                )
            }
            .filter { includeUntouched || !it.untouched }
            .sortedWith(compareBy({ it.chapter }, { -it.mistakeCount }, { it.score }))

    /**
     * 趋势折线数据（降采样到 <= [maxPoints] 个点）。
     *
     * 算法：等宽分桶 + 桶内均值。等比抽点会丢掉峰值，而桶内均值能保留整体走势；
     * 桶数 = maxPoints，最后一个桶吸收余数，保证输出点数 **一定不超过** maxPoints。
     *
     * @param history 按日序号升序的掌握度快照（多知识点合并后的时间序列）
     */
    fun trend(
        history: List<MasterySnapshot>,
        maxPoints: Int = MAX_TREND_POINTS,
    ): TrendData {
        val boundedMax = maxPoints.coerceAtLeast(2)
        val sorted = history.sortedBy { it.dateEpochDayUtc }
        if (sorted.isEmpty()) {
            return TrendData(points = emptyList(), minValue = 0.0, maxValue = 0.0, rawCount = 0)
        }
        val points =
            if (sorted.size <= boundedMax) {
                sorted.map { TrendPoint(epochDayUtc = it.dateEpochDayUtc, value = it.score) }
            } else {
                val bucketSize = (sorted.size + boundedMax - 1) / boundedMax
                val list = ArrayList<TrendPoint>(boundedMax)
                var index = 0
                while (index < sorted.size) {
                    val end = (index + bucketSize).coerceAtMost(sorted.size)
                    val bucket = sorted.subList(index, end)
                    list +=
                        TrendPoint(
                            epochDayUtc = bucket.first().dateEpochDayUtc,
                            value = bucket.sumOf { it.score } / bucket.size,
                        )
                    index = end
                }
                list
            }
        val values = points.map { it.value }
        return TrendData(
            points = points,
            minValue = values.minOrNull() ?: 0.0,
            maxValue = values.maxOrNull() ?: 0.0,
            rawCount = sorted.size,
        )
    }

    /** 把多个知识点的历史合并成一条时间序列（同日取均值）。 */
    fun mergeHistory(masteries: List<Mastery>): List<MasterySnapshot> {
        if (masteries.isEmpty()) return emptyList()
        val byDay = LinkedHashMap<Long, MutableList<Double>>()
        masteries.forEach { mastery ->
            mastery.history.forEach { snapshot ->
                byDay.getOrPut(snapshot.dateEpochDayUtc) { ArrayList() } += snapshot.score
            }
        }
        return byDay.entries
            .map { (day, scores) -> MasterySnapshot(dateEpochDayUtc = day, score = scores.average()) }
            .sortedBy { it.dateEpochDayUtc }
    }

    /**
     * 错因分布饼图。
     *
     * 固定七类的顺序不变（与 ErrorType.FIXED 一致），未出现的类别不画扇区；
     * 角度从 12 点方向顺时针累加，总和恰好 360 度。
     */
    fun pie(distribution: Map<String, Int>): PieData {
        val ordered =
            com.totop.app.domain.model.ErrorType.FIXED.map { type ->
                type.label to (distribution[type.code] ?: 0)
            }
        val total = ordered.sumOf { it.second }
        if (total <= 0) return PieData(slices = emptyList(), total = 0)
        var angle = 0.0
        val slices = ArrayList<PieSlice>(ordered.size)
        ordered.forEachIndexed { index, entry ->
            val label = entry.first
            val count = entry.second
            if (count > 0) {
                val ratio = count.toDouble() / total.toDouble()
                val sweep = ratio * 360.0
                slices +=
                    PieSlice(
                        label = label,
                        count = count,
                        ratio = ratio,
                        startAngle = angle,
                        sweepAngle = sweep,
                        colorIndex = index,
                    )
                angle += sweep
            }
        }
        return PieData(slices = slices, total = total)
    }

    /**
     * 雷达图的顶点坐标（以画布中心为原点、半径 r）。
     *
     * 第 0 个轴指向正上方（-90 度），顺时针均匀分布；返回 (x, y) 列表，
     * y 轴向下为正（Compose Canvas 坐标系）。
     */
    fun radarVertices(
        count: Int,
        radius: Float,
        centerX: Float,
        centerY: Float,
        scale: (Int) -> Double,
    ): List<Pair<Float, Float>> {
        if (count <= 0) return emptyList()
        return (0 until count).map { index ->
            val angle = -Math.PI / 2.0 + 2.0 * Math.PI * index.toDouble() / count.toDouble()
            val r = (radius * scale(index).coerceIn(0.0, 1.0)).toFloat()
            val x = centerX + (r * cos(angle)).toFloat()
            val y = centerY + (r * sin(angle)).toFloat()
            x to y
        }
    }

    /** 折线图点的坐标（等距横轴 + 线性纵轴，min/max 自动扩展以避免除零）。 */
    fun lineVertices(
        data: TrendData,
        width: Float,
        height: Float,
    ): List<Pair<Float, Float>> {
        if (data.points.isEmpty()) return emptyList()
        val min = data.minValue
        val max = max(data.maxValue, min + 1.0)
        val stepX = if (data.points.size == 1) 0f else width / (data.points.size - 1).toFloat()
        return data.points.mapIndexed { index, point ->
            val ratio = ((point.value - min) / (max - min)).coerceIn(0.0, 1.0)
            xOf(stepX, index) to (height - (height * ratio).toFloat())
        }
    }

    private fun xOf(
        stepX: Float,
        index: Int,
    ): Float = stepX * index.toFloat()
}
