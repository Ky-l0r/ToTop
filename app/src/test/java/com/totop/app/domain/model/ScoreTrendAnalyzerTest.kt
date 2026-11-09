package com.totop.app.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 趋势口径（docs/学情与成绩趋势.md 第四节）。 */
class ScoreTrendAnalyzerTest {
    private fun exam(
        id: String,
        day: Long,
        kind: ExamKind = ExamKind.MAJOR,
        gradeRank: Int? = null,
        classRank: Int? = null,
        gradeSize: Int? = 480,
        classSize: Int? = 45,
        name: String = "",
        scores: List<ExamScore> = emptyList(),
    ) = ExamRecord(
        id = id,
        occurredOn = day,
        kind = kind,
        name = name,
        gradeSize = gradeSize,
        classSize = classSize,
        gradeRank = gradeRank,
        classRank = classRank,
        scores = scores,
    )

    private fun score(
        subject: String,
        value: Double,
        full: Double,
        gradeRank: Int? = null,
    ) = ExamScore(subject = subject, score = value, fullScore = full, gradeRank = gradeRank)

    @Test
    @DisplayName("全部科目只保留考全的场次")
    fun allSubjectsKeepsCompleteExamsOnly() {
        val required = setOf("math", "chinese")
        val records =
            listOf(
                exam(
                    "a",
                    10,
                    gradeRank = 50,
                    scores = listOf(score("math", 120.0, 150.0), score("chinese", 130.0, 150.0)),
                ),
                // 缺语文，不进「全部」视图
                exam("b", 20, gradeRank = 40, scores = listOf(score("math", 150.0, 150.0))),
            )
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(requiredSubjects = required))
        assertEquals(1, trend.points.size)
        assertEquals("a", trend.points.first().examId)
    }

    @Test
    @DisplayName("全部科目按各科等权平均而不是总分加权")
    fun allSubjectsUsesEqualWeight() {
        val required = setOf("math", "chinese")
        // 数学 90/150 = 60，语文 90/100 = 90 -> 等权 75；若按总分加权则是 72
        val record =
            exam(
                "a",
                10,
                scores = listOf(score("math", 90.0, 150.0), score("chinese", 90.0, 100.0)),
            )
        val trend = ScoreTrendAnalyzer.analyze(listOf(record), TrendFilter(requiredSubjects = required))
        assertEquals(75.0, trend.points.first().rate, 0.001)
    }

    @Test
    @DisplayName("单科视图取该科全部场次并读该科排名")
    fun subjectViewUsesSubjectRank() {
        val records =
            listOf(
                exam("a", 10, gradeRank = 56, scores = listOf(score("math", 120.0, 150.0, gradeRank = 12))),
                exam(
                    "b",
                    20,
                    kind = ExamKind.MINOR,
                    scores = listOf(score("math", 80.0, 100.0, gradeRank = 30)),
                ),
            )
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(subject = "math"))
        assertEquals(2, trend.points.size)
        assertEquals(12, trend.points[0].gradeRank)
        assertEquals(30, trend.points[1].gradeRank)
        assertEquals(20L, trend.points[1].occurredOn)
    }

    @Test
    @DisplayName("X 轴按次数等距且按日期升序")
    fun axisOrderedByDate() {
        val records =
            listOf(
                exam("late", 30, scores = listOf(score("math", 100.0, 150.0))),
                exam("early", 10, scores = listOf(score("math", 90.0, 150.0))),
            )
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(subject = "math"))
        assertEquals(listOf(1, 2), trend.points.map { it.index })
        assertEquals(listOf("early", "late"), trend.points.map { it.examId })
    }

    @Test
    @DisplayName("没有记录时给出 NO_RECORDS")
    fun emptyGivesNoRecords() {
        val trend = ScoreTrendAnalyzer.analyze(emptyList(), TrendFilter(subject = "math"))
        assertEquals(TrendEmptyReason.NO_RECORDS, trend.emptyReason)
    }

    @Test
    @DisplayName("有考试但一次都没考全时给出 NO_COMPLETE_EXAM")
    fun incompleteGivesNoCompleteExam() {
        val records = listOf(exam("a", 10, scores = listOf(score("math", 100.0, 150.0))))
        val trend =
            ScoreTrendAnalyzer.analyze(
                records,
                TrendFilter(requiredSubjects = setOf("math", "chinese")),
            )
        assertEquals(TrendEmptyReason.NO_COMPLETE_EXAM, trend.emptyReason)
    }

    @Test
    @DisplayName("满分为零的记录不参与计算")
    fun zeroFullScoreExcluded() {
        val record = exam("a", 10, scores = listOf(score("math", 0.0, 0.0), score("chinese", 120.0, 150.0)))
        val trend = ScoreTrendAnalyzer.analyze(listOf(record), TrendFilter(subject = "math"))
        assertTrue(trend.points.isEmpty())
    }

    @Test
    @DisplayName("百分位按人数折算")
    fun percentileFromSize() {
        val point =
            ScorePoint(
                examId = "a",
                occurredOn = 10,
                index = 1,
                name = "",
                kind = ExamKind.MAJOR,
                rate = 80.0,
                subjectCount = 6,
                gradeRank = 56,
                classRank = 8,
                gradeSize = 480,
                classSize = 45,
            )
        assertEquals(12, point.gradePercentile)
        assertEquals(18, point.classPercentile)
    }

    @Test
    @DisplayName("点不足三个不给斜率")
    fun noSlopeWithTwoPoints() {
        val records =
            listOf(
                exam("a", 10, scores = listOf(score("math", 100.0, 150.0))),
                exam("b", 20, scores = listOf(score("math", 120.0, 150.0))),
            )
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(subject = "math"))
        assertNull(trend.slope)
    }

    @Test
    @DisplayName("上升趋势可按斜率估算还差几次")
    fun risingTrendEstimatesRemaining() {
        val records =
            (0..3).map { index ->
                exam(
                    "e" + index,
                    day = 10L + index,
                    scores = listOf(score("math", 60.0 + index * 10.0, 100.0)),
                )
            }
        val trend =
            ScoreTrendAnalyzer.analyze(
                records,
                TrendFilter(subject = "math", target = 95.0),
            )
        // 60,70,80,90；最近 90，斜率 10 -> 还差 1 次
        assertEquals(1, trend.remainingToTarget)
    }

    @Test
    @DisplayName("下降趋势不给预测")
    fun fallingTrendHasNoForecast() {
        val records =
            (0..3).map { index ->
                exam(
                    "e" + index,
                    day = 10L + index,
                    scores = listOf(score("math", 90.0 - index * 10.0, 100.0)),
                )
            }
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(subject = "math", target = 95.0))
        assertNull(trend.remainingToTarget)
    }

    @Test
    @DisplayName("类型筛选只保留对应类型")
    fun kindFilter() {
        val records =
            listOf(
                exam("big", 10, kind = ExamKind.MAJOR, scores = listOf(score("math", 120.0, 150.0))),
                exam("small", 20, kind = ExamKind.MINOR, scores = listOf(score("math", 80.0, 100.0))),
            )
        val trend = ScoreTrendAnalyzer.analyze(records, TrendFilter(subject = "math", kind = ExamKind.MAJOR))
        assertEquals(1, trend.points.size)
        assertEquals("big", trend.points.first().examId)
    }
}
