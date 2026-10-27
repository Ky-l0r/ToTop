package com.totop.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.totop.app.ui.home.HomeScreen
import com.totop.app.ui.hub.HubScreen
import com.totop.app.ui.knowledge.KnowledgePointDetailScreen
import com.totop.app.ui.knowledge.KnowledgeScreen
import com.totop.app.ui.mistakes.MistakesScreen
import com.totop.app.ui.mistakes.ReviewScreen
import com.totop.app.ui.offline.OfflineNoteScreen
import com.totop.app.ui.report.ReportScreen
import com.totop.app.ui.reviewmaterial.ReviewMaterialScreen
import com.totop.app.ui.settings.BackupScreen
import com.totop.app.ui.settings.SettingsScreen
import com.totop.app.ui.theme.Tokens

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
fun ToTopApp(
    navController: NavHostController = rememberNavController(),
    startViewModel: StartViewModel = hiltViewModel(),
) {
    val ready by startViewModel.ready.collectAsStateWithLifecycle()
    val startRoute by startViewModel.startRoute.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.fromRoute(currentRoute) != null

    // 判定完成前只留背景色：
    // 先画首页再跳引导会看到「首页闪一下」，先画引导又会给老用户看到陌生页面。
    if (!ready) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize().background(Tokens.Background),
        )
        return
    }

    Scaffold(
        bottomBar = {
            // 不使用动画：性能规范禁止大面积动画与视差
            if (isTopLevel) {
                FloatingNavBar(
                    currentRoute = currentRoute,
                    onSelect = { destination ->
                        // 已经在这个页面时不做任何导航：
                        // 重复 navigate 会走一遍「出栈 + 入栈 + 重建」，是点击卡顿的常见来源。
                        if (currentRoute == destination.route) return@FloatingNavBar
                        navController.navigate(destination.route) {
                            // 顶层页面之间复用同一份回退栈，避免栈无限增长
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        ToTopNavHost(
            navController = navController,
            startRoute = startRoute,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * 浮动胶囊导航栏。
 *
 * 为什么不用 Material3 的 [NavigationBar]：
 * 1. 它默认高 80dp，且**横向铺满屏幕、四角直角**，在全面屏手机上像一条压在底部的色块，
 *    真机反馈的原话是「太厚了，不好看，而且直角边框缺乏美感」；
 * 2. 它强制「图标在上、文字在下」的竖排，纵向必然占满两行。
 *
 * 现在改成：离屏幕边缘留白、整体圆角 26dp、内容横排（图标在左、文字在右），
 * 高度降到 52dp 左右，视觉上是一块浮在内容之上的胶囊，而不是一条边框。
 * 选中的那一项用浅紫底 + 主色文字，其余保持中性，一眼能看出当前在哪。
 */
@Composable
private fun FloatingNavBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(NAV_BAR_RADIUS), clip = false)
                .clip(RoundedCornerShape(NAV_BAR_RADIUS))
                .background(Tokens.Surface)
                .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(NAV_BAR_RADIUS))
                        .background(if (selected) Tokens.PrimaryContainer else Color.Transparent)
                        .clickable { onSelect(destination) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                    contentDescription = stringResource(destination.labelRes),
                    tint = if (selected) Tokens.Primary else Tokens.OnSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(destination.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Tokens.Primary else Tokens.OnSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** 胶囊半径：取高度的一半附近，保证两端是完整的半圆而不是圆角矩形。 */
private val NAV_BAR_RADIUS = 26.dp

/** 导航宿主。所有既有路由保持不变，只新增首页、功能中心、复习资料、离线记题。 */
@Composable
fun ToTopNavHost(
    navController: NavHostController,
    startRoute: String = Routes.HOME,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startRoute,
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
        composable(route = Routes.ONBOARDING) {
            com.totop.app.ui.onboarding.OnboardingScreen(
                onFinish = {
                    // 看完引导直接进首页：不回退栈里留一个「引导」，否则按返回键会再看到它
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(route = Routes.COMPANION) {
            com.totop.app.ui.companion.CompanionScreen(
                onBack = { navController.popBackStack() },
                onAskAi = {
                    // 陪伴作业里的求助 = 新开一次拍题对话。
                    //
                    // 刻意不把当前内容带过去：陪伴模式里并没有在解某一道题，
                    // 硬塞上下文只会让模型拿到一份错位的题干。
                    navController.navigate(Routes.SOLVE_NEW) {
                        // 清掉已有的拍题页，保证进去是干净的一轮
                        popUpTo(Routes.SOLVE) { inclusive = true }
                        launchSingleTop = true
                        restoreState = false
                    }
                },
            )
        }

        composable(route = Routes.HOME) {
            HomeScreen(
                onOpenCapture = { navController.navigateTopLevel(Routes.SOLVE) },
                onOpenMistakes = { navController.navigate(Routes.MISTAKES) },
                onOpenReviewMaterial = { navController.navigate(Routes.REVIEW_MATERIAL) },
                onOpenOffline = { navController.navigate(Routes.OFFLINE) },
                onOpenHub = { navController.navigateTopLevel(Routes.HUB) },
                onOpenKnowledgePoint = { id -> navController.navigate(Routes.kpointDetail(id)) },
                onOpenCompanion = { navController.navigate(Routes.COMPANION) },
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

        // 同一个界面，但进去时**强制开一条新对话**（陪伴作业的求助入口用）。
        // 单独一条路由而不是加参数：cleanNew 是「进入这一页时的初始动作」，
        // 用参数表达会让同一个路由在重建时反复重新开局，把用户刚拍的题清掉。
        composable(route = Routes.SOLVE_NEW) {
            com.totop.app.ui.solve.SolveRoute(
                onOpenKnowledgePoint = { kpointId ->
                    navController.navigate(Routes.kpointDetail(kpointId))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                startFresh = true,
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
                onOpenCompanion = { navController.navigate(Routes.COMPANION) },
            )
        }

        composable(route = Routes.MISTAKES) {
            MistakesScreen(
                onOpenReview = { navController.navigate(Routes.REVIEW) },
                // 点一道错题 -> 打开「回顾」页，而不是复习页。
                //
                // 旧实现两个入口都跳到 Routes.REVIEW，学生想「看看我当时错在哪」
                // 却被丢进做题流程，这是真机反馈里最直接的一条不满。
                // 回顾是只读的，复习才是有交互的，两者必须分开。
                onOpenMistake = { mistakeId -> navController.navigate(Routes.mistakeDetail(mistakeId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.MISTAKE_DETAIL,
            arguments =
                listOf(
                    navArgument(Routes.MISTAKE_DETAIL_ARG) { type = NavType.StringType },
                ),
        ) { entry ->
            com.totop.app.ui.mistakes.MistakeDetailScreen(
                mistakeId = entry.arguments?.getString(Routes.MISTAKE_DETAIL_ARG).orEmpty(),
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
                onOpenModel = { navController.navigate(Routes.SETTINGS_MODEL) },
                onOpenTeaching = { navController.navigate(Routes.SETTINGS_TEACHING) },
                onOpenStorage = { navController.navigate(Routes.SETTINGS_STORAGE) },
                onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.SETTINGS_MODEL) {
            com.totop.app.ui.settings.ModelSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Routes.SETTINGS_TEACHING) {
            com.totop.app.ui.settings.TeachingSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Routes.SETTINGS_STORAGE) {
            com.totop.app.ui.settings.StorageSettingsScreen(
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenExport = { navController.navigate(Routes.BACKUP) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(route = Routes.SETTINGS_ABOUT) {
            com.totop.app.ui.settings.AboutSettingsScreen(
                onBack = { navController.popBackStack() },
                onReplayOnboarding = {
                    navController.navigate(Routes.ONBOARDING) { launchSingleTop = true }
                },
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
