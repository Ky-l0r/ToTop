package com.totop.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/** 单条公式的渲染结果。 */
sealed class FormulaRenderResult {
    /** 渲染成功；[bitmap] 已按公式实际内容尺寸裁剪。 */
    data class Rendered(val bitmap: Bitmap) : FormulaRenderResult()

    /** 渲染失败（KaTeX 资源缺失、语法错误或超时），调用方必须降级为纯文本。 */
    data class Failed(val reason: String) : FormulaRenderResult()
}

/**
 * 公式渲染句柄：调用方可用它撤销本次渲染，
 * 避免「迟到的结果」覆盖已经降级显示的内容。
 */
class FormulaRequest internal constructor() {
    /**
     * 结果回调。
     *
     * 这里必须处理「结果比回调先到」：WebView 尚未加载完成时，[FormulaRenderer.request]
     * 会立刻判定失败，而调用方往往要等 request 返回之后才挂上 onResult——
     * 旧实现直接丢弃了那次失败，于是这条公式既没有图也没有降级文字，
     * 屏幕上留下一块空白（真机反馈里的「化学式偶尔完全不显示」）。
     * 现在失败先存进 [pendingResult]，挂回调时补发一次。
     */
    internal var onResult: ((FormulaRenderResult) -> Unit)? = null
        set(value) {
            field = value
            val queued = pendingResult
            if (value != null && queued != null) {
                pendingResult = null
                value(queued)
            }
        }

    /** 回调尚未挂上时先收到的结果。 */
    private var pendingResult: FormulaRenderResult? = null

    /** 是否已被调用方撤销（超时降级、Composable 离开组合）。 */
    @Volatile
    internal var cancelled: Boolean = false

    /** 立刻判定失败；回调还没挂上时先存下来。 */
    internal fun failNow(result: FormulaRenderResult) {
        val callback = onResult
        if (callback != null) callback(result) else pendingResult = result
    }

    /** 撤销本次渲染：之后到达的结果会被直接丢弃。 */
    fun cancel() {
        cancelled = true
        onResult = null
        pendingResult = null
    }
}

/**
 * 单例 WebView 公式渲染器（FR-1.3 公式渲染 / 方案 7.1 性能约束）。
 *
 * 设计要点（针对「低端机 + 完全离线」）：
 * 1. **实例复用**：整个进程只创建一个 [WebView]，一次加载 assets/formula_render.html，
 *    所有公式通过 evaluateJavascript 送入同一页面渲染，避免「每条公式一个 WebView」的内存与首帧开销；
 * 2. **串行队列**：WebView 单线程渲染，请求排队执行；
 * 3. **退出即销毁**：页面离开时调用 [dispose]，销毁 WebView 并释放宿主，下次进入页面重新创建；
 * 4. **失败可降级**：资源缺失、语法错误、超时（[RENDER_BUDGET_MILLIS]）一律返回
 *    [FormulaRenderResult.Failed]，由 FormulaText 降级为纯文本；
 * 5. **禁止 MathJax 与任何 CDN**：只加载本地 assets/katex/ 下的 KaTeX。
 *
 * 线程约定：公开方法只在主线程调用；JS 回调先经 [WebView.post] 回到主线程再处理。
 */
@Stable
@SuppressLint("SetJavaScriptEnabled")
class FormulaRenderer private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {
    private var hostView: FrameLayout? = null

    private var webView: WebView? = null

    private val readyState = MutableStateFlow(false)

    private val queue = ArrayDeque<QueuedRequest>()

    private var current: QueuedRequest? = null

    private var nextId: Long = 0L

    private var destroyed: Boolean = false

    /** 是否已经成功渲染过至少一条公式；用于区分「首帧预算」与「常规预算」。 */
    private var renderedOnce: Boolean = false

    /** 页面是否已加载完成并可接收渲染指令。 */
    val isReady: StateFlow<Boolean> = readyState.asStateFlow()

    /** 当前是否已销毁（销毁后必须重新 [obtain]）。 */
    val isDestroyed: Boolean get() = destroyed

    /** 当前有几个页面在用这个渲染器（见 [retainHost]）。 */
    private var hostRefs: Int = 0

    /**
     * 页面级宿主登记。
     *
     * 页面切换过渡期间新旧两页会同时在组合树里：旧页 onDispose 若直接销毁 WebView，
     * 新页刚挂的宿主就成了孤儿，这一页的公式只能降级成源码文本。
     * 因此销毁推迟到最后一个使用方离开。
     */
    fun retainHost() {
        hostRefs++
    }

    /** 页面级宿主注销；最后一个使用方离开时才真正销毁。 */
    fun releaseHost() {
        if (hostRefs > 0) hostRefs--
        if (hostRefs == 0) dispose()
    }

    /** 共享 WebView 宿主是否已挂进组合树（供 FormulaText 判断是否需要兜底挂载）。 */
    fun hasHost(): Boolean = hostView != null && webView != null

    /**
     * 宿主是否**真的**还挂在窗口上。
     *
     * [hasHost] 只说明对象还在：宿主挂在某个列表项里时，那一项被回收后对象仍非空，
     * 但 WebView 已经 detach，此时取像素拿到的是空图。
     * 判断「要不要重新挂」必须看窗口附着状态。
     */
    fun isHostAttached(): Boolean = hostView?.isAttachedToWindow == true

    /**
     * 把 WebView 宿主挂到 Compose 的 AndroidView 上。
     *
     * 只有宿主真正进入组合树后，WebView 的排版与 draw(Canvas) 才可靠；
     * 因此 FormulaText 自己不创建 WebView，而是共用这一个宿主。
     */
    fun attachHost(context: Context): View {
        hostView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val host =
            FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
            }
        val created = createWebView(context)
        host.addView(
            created,
            FrameLayout.LayoutParams(HOST_WIDTH_PX, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        hostView = host
        webView = created
        return host
    }

    private fun createWebView(context: Context): WebView {
        val view = WebView(context.applicationContext)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true
            // 只允许 file:///android_asset 本地资源，禁止一切网络请求
            blockNetworkLoads = true
            allowFileAccess = true
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        // 软件层：保证 WebView 在未获得硬件合成时 draw(Canvas) 仍能取到像素
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.addJavascriptInterface(PageBridge(), BRIDGE_NAME)
        view.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    readyState.value = true
                    drainQueue()
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = true
            }
        view.loadUrl(ASSET_URL)
        return view
    }

    /**
     * 请求渲染一条公式。
     *
     * @param latex LaTeX 源码（不含定界符）
     * @param displayMode true 为行间公式（块级居中），false 为行内公式
     * @param maxWidthPx 允许的最大排版宽度（物理像素），公式实际位图按其内容宽度裁剪
     */
    fun request(
        latex: String,
        displayMode: Boolean,
        maxWidthPx: Int,
    ): FormulaRequest {
        val request = FormulaRequest()
        if (destroyed) {
            // 已销毁（页面退出）：这里只能失败，调用方会降级成原文，下次进页面重新渲染。
            request.failNow(FormulaRenderResult.Failed(IF_WEBVIEW_UNAVAILABLE))
            return request
        }
        val queued =
            QueuedRequest(
                id = nextId++,
                latex = latex,
                displayMode = displayMode,
                maxWidthPx = maxWidthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
                request = request,
            )
        queue.addLast(queued)
        if (!readyState.value) {
            // 页面还没加载完（刚进页面、刚重建 WebView）：先排队，onPageFinished 后统一放行。
            //
            // 旧实现这里直接 failNow。调用方按 LaTeX 源码缓存结果，所以降级出来的原文
            // 会一直挂在屏幕上，表现为「首屏公式偶尔是纯文本、退出重进才好」。
            // 排队 + 一个兜底截止时间，既不会永久占位，也不会白扔首屏那几条公式。
            scheduleNotReadyDeadline(queued)
        }
        drainQueue()
        return request
    }

    /**
     * 未就绪期的兜底：超过 [NOT_READY_BUDGET_MILLIS] 还没放行就判失败。
     *
     * 覆盖「宿主没挂进窗口、WebView 被系统回收」这类永远不会 onPageFinished 的情况，
     * 避免公式位置一直显示占位条。
     */
    private fun scheduleNotReadyDeadline(queued: QueuedRequest) {
        val view = webView ?: return
        view.postDelayed({ failQueued(queued, IF_NOT_READY) }, NOT_READY_BUDGET_MILLIS)
    }

    /** 把一条还在队列里（尚未开始）的请求判失败。 */
    private fun failQueued(
        queued: QueuedRequest,
        reason: String,
    ) {
        if (!queue.remove(queued)) return
        if (!queued.request.cancelled) {
            queued.request.onResult?.invoke(FormulaRenderResult.Failed(reason))
        }
        queued.request.onResult = null
    }

    /** 依次执行队列：WebView 只能串行渲染。 */
    private fun drainQueue() {
        if (current != null || destroyed || !readyState.value) return
        var next = queue.pollFirst()
        while (next != null && next.request.cancelled) {
            next = queue.pollFirst()
        }
        if (next == null) return
        current = next
        val view = webView
        if (view == null) {
            finish(next, FormulaRenderResult.Failed(IF_WEBVIEW_UNAVAILABLE))
            return
        }
        val script =
            "ToTop.render(" + next.id + "," + jsString(next.latex) + "," +
                next.displayMode + "," + next.maxWidthPx + ")"
        view.evaluateJavascript(script, null)
        // 首条公式要等 WebView 首帧合成 + KaTeX 首次解析，300ms 必然不够；
        // 渲染成功过一次之后回到 300ms（真机实测单条公式稳定在 50ms 以内）。
        view.postDelayed({ onRenderTimeout(next.id) }, budgetOfCurrent())
    }

    /** 当前这条公式允许的渲染时间：首条放宽，之后收紧。 */
    private fun budgetOfCurrent(): Long = if (renderedOnce) RENDER_BUDGET_MILLIS else FIRST_RENDER_BUDGET_MILLIS

    /** 调用方（FormulaText）的等待上限：必须不短于渲染器自己给的预算。 */
    fun waitBudgetMillis(): Long = budgetOfCurrent()

    /**
     * 超时兜底：预算内没有回调即判失败并推进队列。
     *
     * 首条超时会原样重排一次再判失败：这一步专门对付「低端机首帧慢」——
     * 重排几乎总能成功，而直接降级会让首屏几行公式全变成 LaTeX 源码。
     */
    private fun onRenderTimeout(id: Long) {
        val active = current ?: return
        if (active.id != id) return
        current = null
        if (!active.retried) {
            active.retried = true
            queue.addFirst(active)
            drainQueue()
            return
        }
        finish(active, FormulaRenderResult.Failed(IF_TIMEOUT))
    }

    /** 完成一个请求并推进队列。 */
    private fun finish(
        request: QueuedRequest,
        result: FormulaRenderResult,
    ) {
        if (current === request) current = null
        // 记住「这台设备已经成功渲染过」：之后不必再给首帧那么长的预算
        if (result is FormulaRenderResult.Rendered) renderedOnce = true
        if (!request.request.cancelled) {
            request.request.onResult?.invoke(result)
        }
        request.request.onResult = null
        if (!destroyed) drainQueue()
    }

    /** JS 回调：公式已排版完成，按实际内容尺寸截图。 */
    private fun onPageRendered(
        id: Long,
        widthPx: Int,
        heightPx: Int,
    ) {
        val active = current ?: return
        if (active.id != id) return
        val width = widthPx.coerceIn(MIN_WIDTH_PX, active.maxWidthPx)
        val height = heightPx.coerceIn(MIN_HEIGHT_PX, MAX_HEIGHT_PX)
        val view = webView
        if (view == null) {
            finish(active, FormulaRenderResult.Failed(IF_WEBVIEW_UNAVAILABLE))
            return
        }
        // 先按内容尺寸排版，再等一帧截图。
        //
        // 桥接回调里的尺寸是页面排版完成后的尺寸（物理像素），而 WebView 当前的实际
        // 布局尺寸是宿主给的宽高，两者不同。measure/layout 之后 WebView 会重新排版，
        // 但 Chromium 的 reflow 与合成是异步的：同一帧内立刻 draw 有可能拿到
        // 「上一次尺寸」的画面（表现为公式只剩左上角一块）。队列本来就是串行的，
        // 多等一帧不影响吞吐，却能把这类偶发空白彻底消掉。
        runCatching {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
        }
        view.post { captureAndFinish(active, width, height) }
    }

    /** 等一帧之后真正取像素并推进队列。 */
    private fun captureAndFinish(
        active: QueuedRequest,
        widthPx: Int,
        heightPx: Int,
    ) {
        // 超时可能已经先把这次请求结束掉了，此时不能再重复 finish（会推进两次队列）
        if (current !== active) return
        val bitmap = capture(widthPx, heightPx)
        finish(
            active,
            if (bitmap == null) FormulaRenderResult.Failed(IF_CAPTURE_FAILED) else FormulaRenderResult.Rendered(bitmap),
        )
    }

    /** JS 回调：渲染失败（KaTeX 缺失或语法错误）。 */
    private fun onPageFailed(
        id: Long,
        message: String,
    ) {
        val active = current ?: return
        if (active.id != id) return
        finish(active, FormulaRenderResult.Failed(message.ifBlank { IF_KATEX_FAILED }))
    }

    /** 把已排版的 WebView 画到 Bitmap 上（布局尺寸由 [onPageRendered] 提前设好）。 */
    private fun capture(
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        val view = webView ?: return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()
    }

    /**
     * 销毁渲染器（页面退出时调用）。
     *
     * 立即清空队列、停止 WebView 的后台任务、释放宿主引用；
     * 之后 [obtain] 会创建一个全新的实例。
     */
    fun dispose() {
        if (destroyed) return
        destroyed = true
        readyState.value = false
        // 队列里排着的请求也要逐个判失败：它们还没开始渲染，不会有人再回调，
        // 不主动收尾就只能在界面上一直显示占位条。
        while (true) {
            val pending = queue.pollFirst() ?: break
            if (!pending.request.cancelled) {
                pending.request.onResult?.invoke(FormulaRenderResult.Failed(IF_DISPOSED))
            }
            pending.request.onResult = null
        }
        current?.let { request ->
            if (!request.request.cancelled) {
                request.request.onResult?.invoke(FormulaRenderResult.Failed(IF_DISPOSED))
            }
            request.request.onResult = null
        }
        current = null
        webView?.let { view ->
            runCatching {
                view.stopLoading()
                view.removeJavascriptInterface(BRIDGE_NAME)
                view.loadUrl("about:blank")
                view.destroy()
            }
        }
        webView = null
        hostView?.let { host ->
            host.removeAllViews()
            (host.parent as? ViewGroup)?.removeView(host)
        }
        hostView = null
        synchronized(LOCK) {
            if (instance === this) instance = null
        }
    }

    /** JS 桥：把页面回调转回主线程。 */
    private inner class PageBridge {
        @JavascriptInterface
        fun onRendered(
            id: String,
            widthPx: Int,
            heightPx: Int,
        ) {
            postToMain { onPageRendered(id.toLongOrNull() ?: -1L, widthPx, heightPx) }
        }

        @JavascriptInterface
        fun onFailed(
            id: String,
            message: String,
        ) {
            postToMain { onPageFailed(id.toLongOrNull() ?: -1L, message) }
        }

        private fun postToMain(block: () -> Unit) {
            val view = webView
            if (view == null) block() else view.post(block)
        }
    }

    private class QueuedRequest(
        val id: Long,
        val latex: String,
        val displayMode: Boolean,
        val maxWidthPx: Int,
        val request: FormulaRequest,
    ) {
        /** 是否已经为超时重排过一次：只给一次机会，避免死循环。 */
        var retried: Boolean = false
    }

    companion object {
        /** 宿主的固定布局宽度（CSS/物理像素）。 */
        const val HOST_WIDTH_PX: Int = 1080

        /** 单条公式的渲染预算（毫秒）：超出即降级为纯文本。 */
        const val RENDER_BUDGET_MILLIS: Long = 300L

        /**
         * 首条公式的渲染预算（毫秒）。
         *
         * 必须覆盖「WebView 首帧合成 + KaTeX 首次解析」：低端机上这两件事加起来常超过 1 秒，
         * 沿用 300ms 会让每次进页面的头几条公式直接降级成 LaTeX 源码。
         * 渲染成功过一次后自动回到 [RENDER_BUDGET_MILLIS]。
         */
        const val FIRST_RENDER_BUDGET_MILLIS: Long = 2000L

        /** 页面未就绪时的排队上限（毫秒）：超过即判失败，避免公式位置一直空占位。 */
        private const val NOT_READY_BUDGET_MILLIS: Long = 5000L

        /** 默认排版宽度上限（物理像素）。 */
        const val DEFAULT_MAX_WIDTH_PX: Int = 720

        private const val MIN_WIDTH_PX: Int = 16

        private const val MAX_WIDTH_PX: Int = 2048

        private const val MIN_HEIGHT_PX: Int = 8

        private const val MAX_HEIGHT_PX: Int = 900

        private const val BRIDGE_NAME: String = "ToTopBridge"

        private const val ASSET_URL: String = "file:///android_asset/formula_render.html"

        private const val IF_NOT_READY: String = "公式渲染器尚未就绪"

        private const val IF_WEBVIEW_UNAVAILABLE: String = "WebView 不可用"

        private const val IF_CAPTURE_FAILED: String = "位图捕获失败"

        private const val IF_TIMEOUT: String = "渲染超时（超过 300ms）"

        private const val IF_KATEX_FAILED: String = "KaTeX 渲染失败"

        private const val IF_DISPOSED: String = "页面已关闭"

        private val LOCK = Any()

        private var instance: FormulaRenderer? = null

        /** 获取进程内唯一的渲染器；已销毁时重建。 */
        fun obtain(context: Context): FormulaRenderer =
            synchronized(LOCK) {
                instance?.takeIf { !it.destroyed }
                    ?: FormulaRenderer(context.applicationContext).also { instance = it }
            }

        /** 当前是否存在存活实例（测试与诊断用）。 */
        fun hasLiveInstance(): Boolean = synchronized(LOCK) { instance?.takeIf { !it.destroyed } != null }

        /** 把字符串转成 JS 字符串字面量（含两侧引号）。 */
        internal fun jsString(raw: String): String {
            val builder = StringBuilder(raw.length + 2)
            builder.append('"')
            raw.forEach { ch ->
                when (ch) {
                    '\\' -> builder.append("\\\\")
                    '"' -> builder.append("\\\"")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    '\u2028' -> builder.append("\\u2028")
                    '\u2029' -> builder.append("\\u2029")
                    else -> if (ch.code < 0x20) builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
                }
            }
            builder.append('"')
            return builder.toString()
        }
    }
}
