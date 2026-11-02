package com.totop.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.TeachingMode
import com.totop.app.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * 首次使用引导（一页一页翻）。
 *
 * 为什么必须有这一页：
 * 这个应用不内置任何 API Key，第一次打开时解题区是灰的、错题本是空的、
 * 知识点页写着「待练习」。新用户看到的是「到处都是 0」，不知道从哪一步开始。
 * 引导的作用不是介绍功能列表，而是交代三件事：
 * **要自己填一个 Key**、**主要动作是拍题**、**数据全在本机**。
 *
 * 交互与内容约定：
 * - 横向翻页，底部有页码点与「下一步 / 开始用」；
 * - 每页只有一个图标 + 一句标题 + 两句话说明，不做长文案；
 * - 最后一页的按钮才是主色实心按钮，前面几页都用次级按钮，避免手快连点跳过；
 * - 「跳过」始终可点：已经知道怎么用的人不应该被强制看完。
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pages = OnboardingPage.PAGES
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()

    // 完成标记一旦置位就只通知一次。
    //
    // 不能直接写成 `if (finished) { onFinish(); return }`：
    // complete() 里已经调用过一次 onFinish，重组时那行会**再调一次**，
    // 于是 navController 被连续导航两次（第二次时 ONBOARDING 已不在栈上）。
    LaunchedEffect(finished) {
        if (finished) onFinish()
    }
    if (finished) return

    Column(
        modifier = modifier.fillMaxSize().background(Tokens.Background).padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "欢迎使用 To Top",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::complete) {
                Text(text = "跳过", color = Tokens.OnSurfaceVariant)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { index ->
            OnboardingPageView(
                page = pages[index],
                mode = mode,
                onModeChange = viewModel::setMode,
            )
        }

        // 页码点：当前页用主色实心，其余用小灰点。
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                            .background(
                                if (active) Tokens.Primary else Tokens.OutlineStrong,
                                CircleShape,
                            ),
                )
            }
        }

        val isLast = pagerState.currentPage == pages.lastIndex
        Button(
            onClick = {
                if (isLast) {
                    viewModel.complete()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text(text = if (isLast) "开始用" else "下一步")
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** 一页引导的内容模型。 */
private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val lines: List<String>,
    /** 是否需要额外的「怎么填」示意图与分步说明（目前只有模型配置那一页）。 */
    val guide: Boolean = false,
    /** 是否在这一页让用户选择讲解方式（首启的唯一一处默认值设定）。 */
    val modeChoice: Boolean = false,
) {
    companion object {
        val PAGES: List<OnboardingPage> =
            listOf(
                OnboardingPage(
                    icon = Icons.Filled.CameraAlt,
                    title = "拍下不会的题",
                    lines =
                        listOf(
                            "对准题目拍照，或从相册选一张；可框选只保留题目那一段。",
                            "一道题跨页也没关系，接着拍，多页会当成同一道题一起看。",
                        ),
                ),
                OnboardingPage(
                    icon = Icons.Filled.Lightbulb,
                    title = "先自己想，再看解答",
                    lines =
                        listOf(
                            "写下你的思路，我会指出第一个出错的地方，而不是只给答案。",
                            "想不出来可以逐级要提示，实在不行再展开完整解答。",
                        ),
                ),
                OnboardingPage(
                    icon = Icons.Filled.Tune,
                    title = "你想让我怎么讲",
                    lines =
                        listOf(
                            "提示引导：先给方向和下一步，答案要你自己点开；适合想自己先想一遍。",
                            "直接讲解：一次给全步骤与答案；适合想快点对答案或已经想通了。",
                        ),
                    modeChoice = true,
                ),
                OnboardingPage(
                    icon = Icons.Filled.Book,
                    title = "错题会自己整理",
                    lines =
                        listOf(
                            "每次解答都会归到对应知识点，错题本能按科目、知识点、错因筛选。",
                            "考前可以一键整理出可打印的复习资料。",
                        ),
                ),
                OnboardingPage(
                    icon = Icons.Filled.Timer,
                    title = "写作业时有陪伴模式",
                    lines =
                        listOf(
                            "计时只记录你真正在写的时长，不设截止时间。",
                            "想不起来的知识点随时问一句，问的时候计时自动暂停。",
                        ),
                ),
                OnboardingPage(
                    icon = Icons.Filled.Key,
                    title = "先填一个模型 Key",
                    lines =
                        listOf(
                            "本应用不内置 Key，用你自己的模型账号，费用也由你直接付给厂商。",
                        ),
                    guide = true,
                ),
                OnboardingPage(
                    icon = Icons.Filled.Lock,
                    title = "数据只留在你自己的手机里",
                    lines =
                        listOf(
                            "错题、掌握度、对话记录全部存在本机，没有开发者服务器。",
                            "题目图片只会发给你自己配置的那家模型厂商。",
                        ),
                ),
            )
    }
}

@Composable
private fun OnboardingPageView(
    page: OnboardingPage,
    mode: TeachingMode,
    onModeChange: (TeachingMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(96.dp)
                    .background(Tokens.PrimaryContainer, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        if (page.guide) {
            // 配置教程单独一屏：三行输入 + 一个校验按钮画成示意图，再列四步，
            // 比在引导里塞两行文字更能让人一次填对。
            ApiSetupGuide(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        page.lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Tokens.Primary.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }
        if (page.modeChoice) {
            Spacer(Modifier.height(10.dp))
            TeachingMode.entries.forEach { candidate ->
                ModeOption(
                    mode = candidate,
                    selected = candidate == mode,
                    onSelect = { onModeChange(candidate) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 讲解方式的一个选项。
 *
 * 选中状态与设置页共用 [com.totop.app.data.repo.SettingsRepository.Keys.MODE]：
 * 这里点一下就直接写库，不等「开始用」——跳过引导的人也算做了选择，
 * 否则他跳过之后回到设置页会看到一个自己没选过的值。
 */
@Composable
private fun ModeOption(
    mode: TeachingMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val background = if (selected) Tokens.PrimaryContainer else Tokens.SurfaceVariant
    val border = if (selected) Tokens.Primary else Color.Transparent
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurface,
            )
            Text(
                text =
                    when (mode) {
                        TeachingMode.GUIDED -> "首屏只给方向和下一步，答案要你自己点开"
                        TeachingMode.DIRECT -> "一次给全步骤与最终答案"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
            )
        }
    }
}
