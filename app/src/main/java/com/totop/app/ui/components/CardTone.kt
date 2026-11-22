package com.totop.app.ui.components

/**
 * 卡片的两档底色。
 *
 * 全应用只留这两种卡片观感，避免同一屏出现「白底带阴影」「浅灰无边界」「白底无边界」三种：
 * - [Raised]：白底 + 1dp 阴影，用于**可点**的入口（首页常用功能、宫格）；
 * - [Tint]：浅灰底、无阴影，用于**内容分组**（指标、列表行、区块）。
 */
enum class CardTone {
    Raised,
    Tint,
}
