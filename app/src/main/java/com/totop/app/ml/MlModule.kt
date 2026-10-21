package com.totop.app.ml

import android.content.Context
import android.util.Log
import com.totop.app.util.LatexRuleTableLoad
import com.totop.app.util.LatexSpokenRuleTable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 端侧 ML 能力的 Hilt 绑定（开发方案「ML 层」）。
 *
 * 绑定的五个能力：
 * - [ImagePreprocessor] -> [ImagePreprocess]：长边 1280 / JPEG 0.78 / EXIF 纠正 / sha256 落盘 / 缩略图；
 * - [OcrRecognizer] -> [OcrEngine]：ML Kit bundled 中文识别（离线，不含 Play Services 依赖）；
 * - [TtsEngine]：系统 TextToSpeech + LaTeX 口语化 + 中文语音包检测；
 * - [LatexSpokenRuleTable]：从 assets 加载的公式口语化规则表（不可变，单例安全）；
 * - [CameraCaptureController] 不参与注入：它由 [CameraPreview] 在 CameraX 绑定成功后回调产出，
 *   生命周期必须跟随 Compose，交给 DI 持有反而会泄漏预览会话。
 *
 * 全部为 [SingletonComponent]：OCR 识别器与 TTS 引擎创建成本高，必须进程内复用。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MlModule {
    /** 图片预处理的接口绑定。 */
    @Binds
    @Singleton
    abstract fun bindImagePreprocessor(impl: ImagePreprocess): ImagePreprocessor

    /** 端侧 OCR 的接口绑定。 */
    @Binds
    @Singleton
    abstract fun bindOcrRecognizer(impl: OcrEngine): OcrRecognizer

    companion object {
        private const val TAG = "MlModule"

        /**
         * 加载 LaTeX 口语化规则表。
         *
         * 资产缺失或格式错误时**不抛异常**，退回 [LatexSpokenRuleTable.EMPTY]，
         * 朗读链路降级为原文（开发方案 FR-6.4 的容错要求）。
         */
        @Provides
        @Singleton
        fun provideLatexSpokenRuleTable(
            @ApplicationContext context: Context,
        ): LatexSpokenRuleTable {
            val load =
                runCatching {
                    LatexSpokenRuleTable.fromInputStream(context.assets.open(LatexSpokenRuleTable.ASSET_PATH))
                }.getOrElse { error ->
                    LatexRuleTableLoad.Failure(com.totop.app.util.LatexRuleTableError.ASSET_MISSING, error.message)
                }
            return when (load) {
                is LatexRuleTableLoad.Success -> load.table
                is LatexRuleTableLoad.Failure -> {
                    Log.w(
                        TAG,
                        "公式朗读规则表不可用：" + load.error.name + " " + load.error.userMessage + " " + (load.detail ?: ""),
                    )
                    load.fallback
                }
            }
        }
    }
}
