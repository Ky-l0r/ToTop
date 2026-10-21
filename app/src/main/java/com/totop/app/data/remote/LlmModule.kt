package com.totop.app.data.remote

import android.content.Context
import com.totop.app.data.prefs.EncryptedSecretStore
import com.totop.app.data.prefs.SecretStore
import com.totop.app.util.AiTraceStore
import com.totop.app.util.InMemoryAiTraceStore
import com.totop.app.util.TraceRecorder
import java.io.InputStreamReader

/**
 * assets 读取（开发方案 12.1 第 3 条：提示词、口语化规则、图谱坐标、种子知识点全部外置 assets）。
 *
 * 只读，不做缓存（提示词由 [PromptRepository] 自行缓存）。
 */
class AndroidAssetReader(private val context: Context) : AssetReader {
    override fun read(path: String): String =
        context.assets.open(path).use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).readText()
        }
}

/**
 * 模型网关的组装入口。
 *
 * 由于 Gradle 脚本由 arch 成员统一维护（本任务不得改 build.gradle.kts），
 * 这里不声明 Inject 构造函数与 Provides，而是提供一个显式的工厂：
 * data / domain / ui 层在需要时直接调用 create，避免改动既有 Hilt 模块定义。
 *
 * ui-solve 用法：ViewModel 中持有 LlmModule.create(context) 的结果，
 * 并自建 LlmCallRegistry，在 onCleared() 中调用 registry.cancelAll()。
 */
class LlmModule private constructor(
    val secretStore: SecretStore,
    val llmClient: LlmClient,
    val promptRepository: PromptRepository,
    val cache: LlmCache,
    val traceRecorder: TraceRecorder,
    val gateway: AiGateway,
) {
    companion object {
        /**
         * 创建一套完整的模型网关。
         *
         * @param cacheStore Room cache_entry 表的实现；数据库尚未就绪时可传 [InMemoryCacheStore] 降级
         * @param traceStore ai_trace 表的实现；降级时用内存实现，保证主链路不因写日志失败而中断
         */
        fun create(
            context: Context,
            cacheStore: CacheStore = InMemoryCacheStore(),
            traceStore: AiTraceStore = InMemoryAiTraceStore(),
        ): LlmModule {
            val secretStore = EncryptedSecretStore(context)
            val llmClient = LlmClient()
            val promptRepository = PromptRepository(AndroidAssetReader(context))
            val cache = LlmCache(cacheStore)
            val traceRecorder = TraceRecorder(traceStore)
            val gateway =
                AiGateway(
                    llmClient = llmClient,
                    promptRepository = promptRepository,
                    traceRecorder = traceRecorder,
                    cache = cache,
                )
            return LlmModule(
                secretStore = secretStore,
                llmClient = llmClient,
                promptRepository = promptRepository,
                cache = cache,
                traceRecorder = traceRecorder,
                gateway = gateway,
            )
        }
    }
}
