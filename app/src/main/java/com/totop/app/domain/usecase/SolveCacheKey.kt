package com.totop.app.domain.usecase

import com.totop.app.domain.model.TeachingMode

/**
 * 完整结果缓存键（开发方案 3.2 第 [3] 步、3.4：键 = sha256 + promptVersion + model）。
 *
 * 三个要素缺一不可：
 * - sha256 保证「同一张图（或同一题干）才可能复用」；
 * - promptVersion 保证「提示词一改，旧缓存立刻失效」，否则会把旧版提示词的结果当成新结果；
 * - model 保证「换模型不串用」。
 *
 * 额外把 [mode] 纳入键：同一题在 DIRECT / GUIDED / SOCRATIC 下下发的字段不同，
 * 若共用一个缓存，切换模式后就会泄漏答案（GUIDED 拿到 DIRECT 的结果）。
 * 注意：**落库的标准答案与模式无关**，模式只影响下发裁剪，因此这只影响缓存粒度，不影响数据正确性。
 */
data class SolveCacheKey(
    val sha256: String,
    val promptVersion: String,
    val model: String,
    val mode: TeachingMode,
) {
    init {
        require(sha256.isNotBlank()) { "缓存键的 sha256 不得为空" }
        require(promptVersion.isNotBlank()) { "缓存键的 promptVersion 不得为空" }
        require(model.isNotBlank()) { "缓存键的 model 不得为空" }
    }

    /** 稳定的字符串键（Room cache_entry 主键与 LRU 表键都直接用它）。 */
    val storageKey: String
        get() = sha256 + "|" + promptVersion + "|" + model + "|" + mode.wire

    override fun toString(): String = storageKey

    companion object {
        /** 键分隔符（与 data 层 CacheKey.storageKey 保持同一约定）。 */
        const val SEPARATOR: String = "|"
    }
}
