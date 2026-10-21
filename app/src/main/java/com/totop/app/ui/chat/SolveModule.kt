package com.totop.app.ui.chat

import android.content.Context
import com.totop.app.data.local.AppDatabase
import com.totop.app.data.prefs.EncryptedSecretStore
import com.totop.app.data.prefs.SecretStore
import com.totop.app.data.remote.AiGateway
import com.totop.app.data.remote.LlmModule
import com.totop.app.data.repo.AiTraceRepository
import com.totop.app.data.repo.CacheRepository
import com.totop.app.data.repo.KnowledgeRepository
import com.totop.app.data.repo.RoomAiTraceStore
import com.totop.app.data.repo.RoomCacheStore
import com.totop.app.domain.model.KnowledgePoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 解题页的依赖装配（Hilt）。
 *
 * 为什么这个模块放在 ui/chat：
 * 本任务改动范围限定在 ui/chat 与 ui/components。把「解题页需要的这组依赖」
 * 就近声明在本包内，既满足「ViewModel 不得直接调用 Retrofit」的约束
 * （网络能力只以 [AiGateway] 这个用例门面的形式注入），
 * 又不触碰 data 层已有的 Hilt 定义。
 *
 * 依赖方向：ui -> data(repository / remote) -> domain。
 * ViewModel 只看到 Repository、Gateway 与 ml 层接口，看不到 OkHttp / Retrofit / WebView。
 */
@Module
@InstallIn(SingletonComponent::class)
object SolveModule {
    /** BYOK 三字段的加密存储（EncryptedSharedPreferences + Android Keystore）。 */
    @Provides
    @Singleton
    fun provideSolveSecretStore(
        @ApplicationContext context: Context,
    ): SecretStore = EncryptedSecretStore(context)

    /**
     * 四阶段编排网关。
     *
     * 复用 data 层已有的 [LlmModule.create] 工厂：缓存接 Room 的 cache_entry，
     * 追踪接 ai_trace，与设置页使用的完全是同一套实现，不存在第二份网络代码。
     */
    @Provides
    @Singleton
    fun provideSolveAiGateway(
        @ApplicationContext context: Context,
        cacheRepository: CacheRepository,
        traceRepository: AiTraceRepository,
    ): AiGateway {
        val cacheStore = RoomCacheStore(cacheRepository)
        val traceStore = RoomAiTraceStore(traceRepository)
        return LlmModule.create(context = context, cacheStore = cacheStore, traceStore = traceStore).gateway
    }


    /**
     * 传输层本体。
     *
     * [LlmClient] 无状态（每次调用自带凭据与配置），因此可以安全单例，
     * 让解题网关与复习资料网关共用同一个 OkHttp 连接池——
     * 否则每次生成复习资料都要新建一套连接，在移动网络下握手开销很明显。
     */
    @Provides
    @Singleton
    fun provideLlmTransport(): com.totop.app.data.remote.LlmTransport = com.totop.app.data.remote.LlmClient()

    /** 复习资料生成网关（与解题共用同一个传输层）。 */
    @Provides
    @Singleton
    fun provideReviewMaterialGateway(
        transport: com.totop.app.data.remote.LlmTransport,
    ): com.totop.app.ui.reviewmaterial.ReviewMaterialGateway =
        com.totop.app.ui.reviewmaterial.ReviewMaterialGateway(transport)

    /** 本地裁剪器：按框选把题目区域裁出来再上传。无状态，可直接单例。 */
    @Provides
    @Singleton
    fun provideImageCropper(): com.totop.app.util.ImageCropper = com.totop.app.util.ImageCropper()

    /**
     * 知识点目录读取（阶段 D 的候选清单来源）。
     *
     * 步骤 D 只能从内置知识点树里选 ID，因此候选清单必须来自真实种子数据，
     * 不允许 AI 自造 ID。
     */
    @Provides
    @Singleton
    fun provideKnowledgeCatalogReader(
        @ApplicationContext context: Context,
        database: AppDatabase,
    ): KnowledgeCatalogReader =
        KnowledgeCatalogReader(
            repository =
                KnowledgeRepository(
                    database = database,
                    knowledgeDao = database.knowledgeDao(),
                    masteryDao = database.masteryDao(),
                    seedRepository =
                        com.totop.app.data.local.seed.SeedRepository(
                            database = database,
                            knowledgeDao = database.knowledgeDao(),
                            settingsDao = database.settingsDao(),
                            assetReader = com.totop.app.data.local.seed.AssetsSeedAssetReader(context.assets),
                        ),
                ),
        )
}

/**
 * 知识点候选读取门面：把「加载种子 + 取快照」收敛成一个可注入对象。
 *
 * 懒加载：只有真正需要候选清单时才建目录，避免每次进页面都读全表。
 */
class KnowledgeCatalogReader(
    private val repository: KnowledgeRepository,
) {
    /** 读取全部知识点（必要时先完成首启种子写入）。 */
    suspend fun allPoints(): List<KnowledgePoint> =
        runCatching { repository.catalogEnsuringSeeded().allKnowledgePoints() }.getOrDefault(emptyList())
}
