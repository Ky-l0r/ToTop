package com.totop.app.ml

import com.totop.app.data.prefs.ModelPool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * ModelRouter 决策矩阵测试（开发方案 3.2 第 [5] 步 / 3.3 分级调用规则）。
 *
 * 判定规则：印刷体 且 置信度 >= 0.80 且 无图形 -> 文本模型；否则 -> 视觉模型。
 */
class ModelRouterTest {
    private val pool = ModelPool(textModel = "qwen-plus", visionModel = "qwen-vl-plus")
    private val router = ModelRouter(pool)

    @ParameterizedTest(name = "置信度 {0} 含图 {1} 手写 {2} -> {3}")
    @CsvSource(
        // 置信度, 含图, 手写, 期望是否视觉
        "0.95, false, false, false",
        "0.80, false, false, false",
        "0.7999, false, false, true",
        "0.95, true, false, true",
        "0.95, false, true, true",
        "0.50, true, true, true",
        "1.00, false, false, false",
        "0.00, false, false, true",
    )
    @DisplayName("路由决策矩阵：三个条件任一不满足即走视觉模型")
    fun routingMatrix(
        confidence: Double,
        hasFigure: Boolean,
        handwritten: Boolean,
        expectVision: Boolean,
    ) {
        val decision =
            router.route(
                avgConfidence = confidence,
                hasFigure = hasFigure,
                isHandwritten = handwritten,
            )
        assertEquals(expectVision, decision.isVision, decision.reason)
        assertEquals(
            if (expectVision) "qwen-vl-plus" else "qwen-plus",
            decision.model,
        )
        assertEquals(expectVision, decision.attachImage, "视觉档必须带图，文本档不得带图")
    }

    @Test
    @DisplayName("阈值 0.80 属于「不低于」，恰好等于时走文本档")
    fun thresholdBoundary() {
        val atThreshold =
            ModelRouter.decideWith(
                OcrSnapshot(text = "求 f(x) 的最小值", avgConfidence = 0.80),
                pool,
            )
        assertFalse(atThreshold.isVision)

        val belowThreshold =
            ModelRouter.decideWith(
                OcrSnapshot(text = "求 f(x) 的最小值", avgConfidence = 0.799999),
                pool,
            )
        assertTrue(belowThreshold.isVision)
    }

    @Test
    @DisplayName("OCR 失败（文本不可用）必须走视觉档，否则没有可用输入")
    fun emptyTextForcesVision() {
        val decision =
            ModelRouter.decideWith(
                OcrSnapshot(text = " ", avgConfidence = 0.99, hasFigure = false, isHandwritten = false),
                pool,
            )
        assertTrue(decision.isVision)
        assertTrue(decision.reason.contains("未识别出可用文字"))
    }

    @Test
    @DisplayName("视觉能力不可用时退化为文本档并说明原因，绝不静默")
    fun visionUnavailableFallsBackToText() {
        val routerNoVision = ModelRouter(pool, visionAvailable = false)
        val decision = routerNoVision.route(OcrSnapshot("题目", avgConfidence = 0.5, hasFigure = true))
        assertFalse(decision.isVision)
        assertEquals("qwen-plus", decision.model)
        assertTrue(decision.reason.contains("无视觉模型"), decision.reason)
        assertFalse(decision.attachImage)
    }

    @Test
    @DisplayName("可注入自定义决策：policy 返回非空时覆盖默认策略")
    fun injectablePolicy() {
        val custom =
            ModelRouter(
                modelPool = pool,
                policy = { _, p -> RouteDecision(RouteTarget.TEXT, p.textModel, "强制走文本档（灰度实验）", false) },
            )
        val decision = custom.route(OcrSnapshot("含图题目", avgConfidence = 0.99, hasFigure = true))
        assertFalse(decision.isVision)
        assertEquals("强制走文本档（灰度实验）", decision.reason)
    }

    @Test
    @DisplayName("置信度越界（0..100 或负数）先裁剪再判定")
    fun confidenceClamped() {
        val overHundred = ModelRouter.decideWith(OcrSnapshot("题目", avgConfidence = 95.0), pool)
        assertFalse(overHundred.isVision, "95 应被裁剪为 1.0 后走文本档")
        val negative = ModelRouter.decideWith(OcrSnapshot("题目", avgConfidence = -1.0), pool)
        assertTrue(negative.isVision, "-1 应被裁剪为 0.0 后走视觉档")
    }

    @Test
    @DisplayName("文本档原因包含具体置信度，便于用户理解为何这次更贵/更便宜")
    fun reasonIsReadable() {
        val cheap = ModelRouter.decideWith(OcrSnapshot("题目", avgConfidence = 0.93), pool)
        assertTrue(cheap.reason.contains("0.93"), cheap.reason)
        assertTrue(cheap.reason.contains("文本模型"), cheap.reason)
        assertTrue(cheap.isCheap)

        val expensive = ModelRouter.decideWith(OcrSnapshot("题目", avgConfidence = 0.42), pool)
        assertTrue(expensive.reason.contains("0.42"), expensive.reason)
        assertTrue(expensive.reason.contains("视觉模型"), expensive.reason)
    }

    @Test
    @DisplayName("视觉模型名为空时回退到文本模型，避免拼出空模型名")
    fun blankVisionModelFallsBack() {
        val singlePool = ModelPool(textModel = "deepseek-chat", visionModel = "deepseek-chat")
        val decision = ModelRouter.decideWith(OcrSnapshot("题目", avgConfidence = 0.1), singlePool)
        assertEquals("deepseek-chat", decision.model)
        assertTrue(decision.isVision, "仍标记为视觉档，是否携带图片由 attachImage 决定")
    }
}
