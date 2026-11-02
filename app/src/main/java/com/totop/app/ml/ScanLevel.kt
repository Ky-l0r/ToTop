package com.totop.app.ml

/**
 * 拍照增强的强度档位（用户可在设置里改）。
 *
 * 为什么做成四档而不是一个开关：增强的代价与场景强相关——
 * 印刷体试卷适合「强」，浅铅笔手写与彩色图表在「强」下容易丢笔画或压成死白；
 * 只给「开/关」等于逼用户在「糊」和「脏」之间二选一。
 *
 * [gain] 是亮度拉伸的对比系数（见 [ScanEnhance]）：0 表示关闭。
 */
enum class ScanLevel(
    val key: String,
    val label: String,
    val gain: Float,
    val description: String,
) {
    OFF(
        key = "off",
        label = "关闭",
        gain = 0f,
        description = "上传原图。光线很好时用它，避免任何处理损失。",
    ),
    WEAK(
        key = "weak",
        label = "弱",
        gain = 1.6f,
        description = "只提亮背景。适合浅铅笔手写、彩色图表多的卷子。",
    ),
    STANDARD(
        key = "standard",
        label = "标准",
        gain = 2.2f,
        description = "接近扫描件。印刷体与普通手写都适用，默认。",
    ),
    STRONG(
        key = "strong",
        label = "强",
        gain = 2.8f,
        description = "背景压到纯白、笔画压到纯黑。适合阴天、阴影下的白纸。",
    ),
    ;

    /** 是否真的要做处理。 */
    val enabled: Boolean get() = gain > 0f

    companion object {
        /** 默认档位：标准。 */
        val DEFAULT: ScanLevel = STANDARD

        /** 由持久化的 key 还原；未知值回落默认，不因为存了个坏值就关掉增强。 */
        fun fromKey(key: String?): ScanLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
