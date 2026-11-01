package com.totop.app.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 渲染句柄的结果投递契约。
 *
 * 对应真机反馈「化学式偶尔完全不显示」：WebView 还没加载完时请求会立刻失败，
 * 而调用方在拿到句柄之后才挂回调，旧的实现把那次失败直接丢掉了——
 * 公式既没有图也没有降级文字，屏幕上只剩一块空白。
 */
@DisplayName("公式渲染句柄")
class FormulaRequestTest {
    @Test
    @DisplayName("失败先于回调到达时会补发")
    fun `补发先到的失败`() {
        val request = FormulaRequest()
        request.failNow(FormulaRenderResult.Failed("渲染器尚未就绪"))
        var received: FormulaRenderResult? = null
        request.onResult = { received = it }
        assertTrue(received is FormulaRenderResult.Failed, "回调挂上后应立即收到此前那次失败")
        assertEquals("渲染器尚未就绪", (received as FormulaRenderResult.Failed).reason)
    }

    @Test
    @DisplayName("撤销之后不再投递")
    fun `撤销后丢弃`() {
        val request = FormulaRequest()
        request.failNow(FormulaRenderResult.Failed("超时"))
        request.cancel()
        var called = false
        request.onResult = { called = true }
        assertTrue(!called, "cancel 之后不应该再触发回调")
    }

    @Test
    @DisplayName("回调已挂上时直接投递，不重复")
    fun `直接投递`() {
        val request = FormulaRequest()
        var count = 0
        request.onResult = { count++ }
        request.failNow(FormulaRenderResult.Failed("一次"))
        request.failNow(FormulaRenderResult.Failed("两次"))
        assertEquals(2, count, "每次 failNow 都应立刻回调；补发只针对挂回调之前的那一次")
    }
}
