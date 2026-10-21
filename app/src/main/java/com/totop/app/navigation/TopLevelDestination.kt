package com.totop.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.ui.graphics.vector.ImageVector
import com.totop.app.R

/**
 * 底部导航的三个顶层目的地。顺序即展示顺序。
 *
 * 为什么从 5 项精简到 3 项：
 * 5 个图标挤在底部会让每个入口都变小、文案变挤，低端机窄屏尤其明显；
 * 错题本、知识点、学情报告、设置这些「低频但重要」的功能改为从功能中心进入，
 * 首页仍然一键直达，不丢可达性。
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(Routes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    Solve(Routes.SOLVE, R.string.nav_solve, Icons.Filled.CameraAlt, Icons.Outlined.CameraAlt),
    Hub(Routes.HUB, R.string.nav_hub, Icons.Filled.Menu, Icons.Outlined.Menu),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? = entries.firstOrNull { it.route == route }
    }
}
