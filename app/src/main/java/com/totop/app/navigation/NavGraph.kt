package com.totop.app.navigation

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.totop.app.ui.common.ImmersiveHost
import com.totop.app.ui.common.LocalImmersiveHost
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
import kotlinx.coroutines.delay

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

    // 沉浸态：框选页要把底部导航栏暂时藏掉，把整块高度让给图片。
    // 状态放在外壳是因为导航栏本身由外壳绘制，页面没有能力改变它占的这块空间。
    val immersiveState = remember { mutableStateOf(false) }
    val immersiveHost = remember { ImmersiveHost { active -> immersiveState.value = active } }

    // 启动动画：判定完成之后仍要停留一小段时间。
    //
    // 只等 ready 是不够的——本机读设置通常几十毫秒，品牌页会一闪而过，
    // 观感上反而像「闪屏」。留 620ms 让淡入淡出完整走完，同时不掩盖真实耗时：
    // 若判定本身更久（首次启动要建库），动画会一直停留到判定结束。
    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_VISIBLE_MS)
        splashDone = true
    }
    val showSplash = !(ready && splashDone)

    Box(modifier = Modifier.fillMaxSize()) {
        if (ready) {
            CompositionLocalProvider(LocalImmersiveHost provides immersiveHost) {
                Scaffold(
                    bottomBar = {
                        // 沉浸态（框选页）不画底栏：那一页的全部意义就是「把图放大看清」，
                        // 底栏占掉的 80-90dp 正好是能多框一行字的高度。
                        //
                        // 不做出现/消失动画：只在顶层页面常驻，动画只会让底部栏闪一下
                        if (isTopLevel && !immersiveState.value) {
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
        }
        // 启动动画覆盖在内容之上淡出，避免「先看首页再盖一层」造成的跳动
        AnimatedVisibility(
            visible = showSplash,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)),
        ) {
            com.totop.app.ui.common.SplashScreen()
        }
    }
}

/**
 * 启动动画最短展示时长。
 *
 * 0.5.1 从 700ms 提到 1200ms：真机反馈「一闪而过，P 刚出来就没了」。
 * 时间账拆开看——五个字母逐字淡入 350ms、末字淡入完成约 510ms，
 * 剩下约 690ms 是「全部出现后定格」，让「写完了」这个动作被看见；
 * 之后才是 260ms 的淡出。
 *
 * 不掩盖真实耗时：判定本身更久（首次启动要建库）时，动画会一直停留到判定结束。
 */
private const val SPLASH_MIN_VISIBLE_MS: Long = 1200L

/** 进入过渡时长（毫秒）。比系统默认短，避免「点了没反应」的观感。 */
private const val TRANSITION_ENTER_MS: Int = 260

/** 退出过渡时长（毫秒）。退出比进入更短，让新页面尽快成为视觉主体。 */
private const val TRANSITION_EXIT_MS: Int = 200

/** 底栏同级切换只有淡入淡出：横移会把「并列」说成「层级」，见 0.5.0 需求定稿。 */
private const val TRANSITION_TAB_MS: Int = 180

/**
 * 位移幅度 = 屏宽 / 该值。
 *
 * 上一版取 18（约 5.5%，20dp 左右），在 360dp 窄屏上等于没动；
 * 真机反馈「完全看不到过渡动画」。10 约等于 10%，方向一眼可辨，又不至于像翻书。
 */
private const val TRANSITION_SLIDE_DIVISOR: Int = 10

/** 进入时的起始缩放。只做 1.5% 的轻微推近，与横移一起构成「往里走」的层次感。 */
private const val TRANSITION_ENTER_SCALE: Float = 0.985f

/**
 * 动画时长的下限系数（相对系统「动画时长缩放」）。
 *
 * 系统设 0.5x 时，Compose 会把我们写的时长再砍一半：260ms 变 130ms、位移又只有 10%，
 * 观感上就是「没有动画」。这里反过来补偿，保证等效时长不低于 0.8 倍：
 *
 *     scale = 0.5 -> 系数 1.6 -> 实际 260 * 1.6 * 0.5 = 208ms（等效 0.8）
 *
 * 系统把动画完全关掉（scale <= 0）时不补偿：那是用户明确的无障碍选择，不该对着干。
 */
private const val MOTION_SCALE_FLOOR: Float = 0.8f

/** 二级页进入：淡入 + 横向位移 + 轻微推近；退栈时位移方向相反。 */
private fun slideEnter(
    ms: Int,
    fromLeft: Boolean = false,
): EnterTransition {
    val direction = if (fromLeft) -1 else 1
    return fadeIn(tween(ms, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(ms, easing = FastOutSlowInEasing)) { width ->
            direction * width / TRANSITION_SLIDE_DIVISOR
        } +
        scaleIn(tween(ms, easing = FastOutSlowInEasing), initialScale = TRANSITION_ENTER_SCALE)
}

/**
 * 是否是「底栏同级切换」。
 *
 * 两个顶层页面之间来回切时不做横移：它们的层级是并列，
 * 横移会让人以为自己在进/退页面，反而更迷惑。
 */
private fun isTabSwitch(
    from: NavBackStackEntry,
    to: NavBackStackEntry,
): Boolean =
    TopLevelDestination.fromRoute(from.destination.route) != null &&
        TopLevelDestination.fromRoute(to.destination.route) != null

/**
 * 底栏同级切换的入场：淡入 + 极轻的上浮。
 *
 * 只淡入时，180ms 的透明度变化在低端机上几乎看不出（真机反馈「切页面没有动画」）；
 * 加一点上浮既能被眼睛捕捉到，又不会把并列关系说成层级关系——横移才是层级语义。
 * 幅度取高度的 1/50，360dp 宽屏上约 8dp，与启动页字母的上移量一致。
 */
private fun tabEnter(ms: Int): EnterTransition =
    fadeIn(tween(ms, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(ms, easing = FastOutSlowInEasing)) { height -> height / 50 }

/** 把基准时长换算成实际写入 [tween] 的时长（含系统缩放补偿）。 */
private fun durationMs(
    base: Int,
    factor: Float,
): Int = (base * factor).toInt().coerceAtLeast(1)

/**
 * 读取系统「动画时长缩放」并换算成补偿系数。
 *
 * 返回值 1 表示照常；大于 1 表示系统把动画压短了，需要反向拉长。
 * 系统关闭动画（0）时返回 1——此时 Compose 本来就不会走动画，尊重用户的无障碍选择。
 */
@Composable
private fun rememberMotionCompensation(): Float {
    val context = LocalContext.current
    return remember(context) {
        val scale =
            runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }.getOrDefault(1f)
        when {
            scale <= 0f -> 1f
            scale < MOTION_SCALE_FLOOR -> MOTION_SCALE_FLOOR / scale
            else -> 1f
        }
    }
}

/**
 * 浮动胶囊导航栏（四项：首页 / 拍题 / 陪伴 / 功能中心）。
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
    val items = TopLevelDestination.entries
    // 当前项下标：路由不在四项里时（例如二级页）停在第一项，滑块不会跑到行外
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(NAV_BAR_RADIUS), clip = false)
                .clip(RoundedCornerShape(NAV_BAR_RADIUS))
                .background(Tokens.Surface)
                .padding(horizontal = 5.dp, vertical = 5.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gap = NAV_ITEM_GAP
            val itemWidth = (maxWidth - gap * (items.size - 1)) / items.size
            // 滑块：选中项之间的位移，而不是每项各自换底色。
            //
            // 每项各画一块底色时，切换是「左边灭、右边亮」两个独立动作，
            // 眼睛读到的是一次闪烁；连续位移才读得出「选中项换到了这里」。
            val targetOffset = (itemWidth + gap) * selectedIndex
            val offsetX by
                animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = tween(NAV_INDICATOR_MS, easing = FastOutSlowInEasing),
                    label = "nav-indicator-offset",
                )
            Box(
                modifier =
                    Modifier
                        .offset(x = offsetX)
                        .width(itemWidth)
                        .height(NAV_ITEM_HEIGHT)
                        .clip(RoundedCornerShape(NAV_BAR_RADIUS))
                        .background(Tokens.PrimaryContainer),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { destination ->
                    val selected = currentRoute == destination.route
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(NAV_ITEM_HEIGHT)
                                .clip(RoundedCornerShape(NAV_BAR_RADIUS))
                                .clickable { onSelect(destination) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = stringResource(destination.labelRes),
                            tint = if (selected) Tokens.Primary else Tokens.OnSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
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
    }
}

/** 导航项之间的间隙。滑块位移按「项宽 + 间隙」累计，与 Row 的 spacedBy 必须一致。 */
private val NAV_ITEM_GAP = 2.dp

/** 导航项高度。 */
private val NAV_ITEM_HEIGHT = 42.dp

/** 滑块位移时长：与底栏淡入同量级，短到不拖沓，又足以被看见是「滑过去」。 */
private const val NAV_INDICATOR_MS: Int = 220

/** 胶囊半径：取高度的一半附近，保证两端是完整的半圆而不是圆角矩形。 */
private val NAV_BAR_RADIUS = 26.dp

/** 导航宿主。所有既有路由保持不变，只新增首页、功能中心、复习资料、离线记题。 */
@Composable
fun ToTopNavHost(
    navController: NavHostController,
    startRoute: String = Routes.HOME,
    modifier: Modifier = Modifier,
) {
    // 系统动画缩放补偿：见 MOTION_SCALE_FLOOR 的说明。
    val motionFactor = rememberMotionCompensation()
    val enterMs = durationMs(TRANSITION_ENTER_MS, motionFactor)
    val exitMs = durationMs(TRANSITION_EXIT_MS, motionFactor)
    val tabMs = durationMs(TRANSITION_TAB_MS, motionFactor)

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        // 页面切换：淡入淡出 + 横向位移 + 轻微推近。
        //
        // 为什么今年这版必须重调参数：
        // 上一版是 180ms / 120ms + 屏宽 1/18 位移，三层叠加之后几乎不可见——
        // 位移只有 20dp、系统动画缩放再砍一半、目标页首帧还在查库（动画在空白期就跑完）。
        // 真机反馈的原话是「完全看不到过渡动画」，因此：
        // - 时长提到 260 / 200ms，位移提到屏宽的 1/10，进入时 0.985 推近；
        // - 底栏同级切换只淡入：它们的层级是并列，横移会误导方向感；
        // - 时长按系统缩放补偿，0.5x 用户也能看到动画。
        enterTransition = {
            if (isTabSwitch(initialState, targetState)) {
                tabEnter(tabMs)
            } else {
                slideEnter(enterMs)
            }
        },
        exitTransition = {
            fadeOut(tween(exitMs, easing = FastOutSlowInEasing))
        },
        popEnterTransition = {
            if (isTabSwitch(initialState, targetState)) {
                tabEnter(tabMs)
            } else {
                slideEnter(enterMs, fromLeft = true)
            }
        },
        popExitTransition = {
            fadeOut(tween(exitMs, easing = FastOutSlowInEasing))
        },
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

        // 陪伴是顶层页（底栏第三项）：不显示返回箭头，问在页内直接用语音或打字。
        // 相机走 SOLVE_NEW：拍下来的是一道题，要的是完整解答，不该塞进「随手问」的一轮对话。
        composable(route = Routes.COMPANION) {
            com.totop.app.ui.companion.CompanionScreen(
                onOpenCamera = { navController.navigate(Routes.SOLVE_NEW) },
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
                onOpenCompanion = { navController.navigateTopLevel(Routes.COMPANION) },
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
                onOpenCompanion = { navController.navigateTopLevel(Routes.COMPANION) },
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
