package com.totop.app.ui.reviewmaterial

import android.content.Context

/**
 * 复习资料提示词模板（外置 assets）。
 *
 * 为什么外置而不是写在 Kotlin 里：
 * 1. 工程约束：提示词一律放 `assets/prompts/`，不许硬编码进代码；
 * 2. 这份模板要随教研口径反复调整（学科骨架、字数、加粗规则），
 *    外置后改文案不用碰编译产物，也便于和 `prompt_version.json` 对照；
 * 3. 模板里含 `{}` 与 `**`，直接写 Kotlin 原始字符串很容易被转义搞乱。
 *
 * 只缓存一次：模板是编译期资源，进程内不会变。
 */
object ReviewMaterialPrompt {
    /** 相对 assets 根目录的路径。 */
    const val ASSET_PATH: String = "prompts/review_material.md"

    @Volatile
    private var cached: String? = null

    /**
     * 读取模板全文。
     *
     * 读不到时抛异常而不是返回空串：空 system 提示词会让模型自由发挥，
     * 生成一份结构对不上的文本，最后由 JSON 解析报一个看不出原因的错误。
     * 调用方应把异常转成「提示词资源缺失」的可读失败。
     */
    fun load(context: Context): String {
        cached?.let { return it }
        val text =
            context.assets.open(ASSET_PATH).use { stream ->
                stream.reader(Charsets.UTF_8).readText()
            }
        require(text.isNotBlank()) { "提示词模板为空：" + ASSET_PATH }
        cached = text
        return text
    }
}
