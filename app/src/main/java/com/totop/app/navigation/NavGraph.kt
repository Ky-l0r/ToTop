package com.totop.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import com.totop.app.ui.hub.HubScreen
import com.totop.app.ui.knowledge.KnowledgePointDetailScreen
import com.totop.app.ui.knowledge.KnowledgeScreen
import com.totop.app.ui.mistakes.MistakesScreen
import com.totop.app.ui.offline.OfflineNoteScreen
import com.totop.app.ui.mistakes.ReviewScreen
import com.totop.app.ui.report.ReportScreen
import com.totop.app.ui.reviewmaterial.ReviewMaterialScreen
import com.totop.app.ui.settings.BackupScreen
import com.totop.app.ui.settings.SettingsScreen
import com.totop.app.ui.home.HomeScreen

/**
 * 应用外壳：Scaffold + 底部三项导航 + 导航宿主。
 *
 * 导航结构（本次重构的核心变化）：
 * - 启动页由「解题」改为「首页」，让用户一进来先看到学习情况；
 * - 底部只保留 首页 / 拍题 / 功能中心 三项；
 * - 错题本、知识点、学情、设置、复习资料、离线记题全部可从功能中心进入，
 *   路由本身保留不动，因此**既有页面与其数据访问完全复用**，不丢任何功能。
 *
 * 二级页面（知识点详情、复习、备份、复习资料）隐藏底部栏，由返回键回到上一页。
 */
@Composable
fun ToTopApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.fromRoute(currentRoute) != null

    Scaffold(
        bottomBar = {
            // 不使用动画：性能规范禁止大面积动画与视差
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                // 已经在这个页面时不做任何导航：
                                // 重复 navigate 会走一遍「出栈 + 入栈 + 重建」，是点击卡顿的常见来源。
                                if (currentRoute == destination.route) return@NavigationBarItem
                                navController.navigate(destination.route) {
                                    // 顶层页面之间复用同一份回退栈，避免栈无限增长
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector =
                                        if (currentRoute == destination.route) {
                                            destination.selectedIcon
                                        } else {
                                            destination.unselectedIcon
                                        },
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(text = stringResource(destination.labelRes)) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        ToTopNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** 导航宿主。所有既有路由保持不变，只新增首页、功能中心、复习资料、离线记题。 */
@Composable
fun ToTopNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        // 页面切换动画全部关掉。
        //
        // 为什么必须关：Compose Navigation 默认带 enter/exit 过渡（淡入 + 位移），
        // 首页、错题、学情这些页面都要在首帧查询数据库（错题数、知识点分布、复习计划），
        // 过渡动画期间目标页面正在做 IO 与组装，低端机上就表现为"点了没反应、然后突然跳过来"。
        // 直接切换虽然少了动效，但点击即到位，观感反而更快。
        // 这也与项目既有规范「禁止大面积动画」一致。
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(route = Routes.HOME) {
            HomeScreen(
                onOpenCapture = { navController.navigateTopLevel(Routes.SOLVE) },
                onOpenMistakes = { navController.navigate(Routes.MISTAKES) },
                onOpenReviewMaterial = { navController.navigate(Routes.REVIEW_MATERIAL) },
                onOpenOffline = { navController.navigate(Routes.OFFLINE) },
                onOpenHub = { navController.navigateTopLevel(Routes.HUB) },
                onOpenKnowledgePoint = { id -> navController.navigate(Routes.kpointDetail(id)) },
            )
        }

        composable(route = Routes.SOLVE) {
            // 对话式拍题：全屏相机 + 框选 + 多页，全部由 SolveRoute 统一编排。
            // 旧的 SolveScreen（卡片堆叠式表单）已删除——保留两套界面会让后续改动分叉。
            com.totop.app.ui.solve.SolveRoute(
                onOpenKnowledgePoint = { kpointId ->
                    navController.navigate(Routes.kpointDetail(kpointId))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(route = Routes.HUB) {
            HubScreen(
                onOpenMistakes = { navController.navigate(Routes.MISTAKES) },
                onOpenKnowledge = { navController.navigate(Routes.KNOWLEDGE) },
                onOpenReport = { navController.navigate(Routes.REPORT) },
                onOpenReviewMaterial = { navController.navigate(Routes.REVIEW_MATERIAL) },
                onOpenOffline = { navController.navigate(Routes.OFFLINE) },
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(route = Routes.MISTAKES) {
            MistakesScreen(
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                onOpenMistake = { navController.navigate(Routes.REVIEW) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.KNOWLEDGE) {
            KnowledgeScreen(
                onOpenKnowledgePoint = { kpointId ->
                    navController.navigate(Routes.kpointDetail(kpointId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.REPORT) {
            ReportScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Routes.SETTINGS) {
            SettingsScreen(
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenExport = { navController.navigate(Routes.BACKUP) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.REVIEW_MATERIAL) {
            ReviewMaterialScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Routes.OFFLINE) {
            OfflineNoteScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.KPOINT_DETAIL,
            arguments =
                listOf(
                    navArgument(Routes.KPOINT_DETAIL_ARG) { type = NavType.StringType },
                ),
        ) { entry ->
            KnowledgePointDetailScreen(
                kpointId = entry.arguments?.getString(Routes.KPOINT_DETAIL_ARG).orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenKnowledgePoint = { kpointId ->
                    navController.navigate(Routes.kpointDetail(kpointId)) {
                        launchSingleTop = true
                    }
                },
                onOpenMistake = { navController.navigate(Routes.MISTAKES) },
            )
        }

        composable(route = Routes.REVIEW) {
            ReviewScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Routes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }
    }
}

/** 顶层导航跳转：复用回退栈，避免重复入栈。 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
