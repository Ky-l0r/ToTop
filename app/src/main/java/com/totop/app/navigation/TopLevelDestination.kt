package com.totop.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.totop.app.R

/**
 * 底部导航的四个顶层目的地。顺序即展示顺序。
 *
 * 从 5 项精简到 3 项、再回到 4 项：
 * 首轮把错题本、知识点、报告、设置收进功能中心，底部只留「首页 / 拍题 / 功能中心」；
 * 0.5.0 把陪伴作业提上来——学生写作业时最想要的是「随时问一句」，
 * 藏进二级页面等于没有。它与拍题同级、等宽，不做额外放大。
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(Routes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    Solve(Routes.SOLVE, R.string.nav_solve, Icons.Filled.CameraAlt, Icons.Outlined.CameraAlt),
    CompanionTab(Routes.COMPANION, R.string.nav_companion, Icons.Filled.Timer, Icons.Outlined.Timer),
    Hub(Routes.HUB, R.string.nav_hub, Icons.Filled.Menu, Icons.Outlined.Menu),
    ;

    companion object {
        fun fromRoute(route: String?): TopLevelDestination? = entries.firstOrNull { it.route == route }
    }
}
