package com.totop.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totop.app.data.repo.SolveSession
import com.totop.app.ui.theme.Tokens

/**
 * 会话侧边栏（多对话管理）。
 *
 * 为什么从「对话框」改成从左侧滑出的侧边栏：
 * 旧实现把历史对话塞进一个居中的 AlertDialog，能点但很别扭——
 * 一次只看得到三四条、遮住整个对话、关掉才能继续问，
 * 学生实际用起来的感受是「这个功能纯属摆设」。
 * 主流对话式客户端（包括网页版）都把会话列表放在左侧常驻栏，
 * 因为它需要**边看边切**：扫一眼标题，点进另一条，再回来。
 *
 * 布局：
 * ```
 * ┌─────────────┬──────────────┐
 * │ 新建对话      │              │
 * │─────────────│   当前对话     │
 * │ 今天          │   （被压暗）    │
 * │  会话 A       │              │
 * │  会话 B       │              │
 * │ 更早          │              │
 * │  会话 C       │              │
 * └─────────────┴──────────────┘
 * ```
 *
 * @param sessions 全部会话（已按最近活动倒序）
 * @param currentSessionId 当前打开的会话；用于高亮
 * @param onOpen 点击某条会话
 * @param onDelete 删除某条会话
 * @param onStartNew 新建对话
 * @param onDismiss 关闭侧边栏
 */
@Composable
fun SessionDrawer(
    sessions: List<SolveSession>,
    currentSessionId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onStartNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .width(DRAWER_WIDTH)
                    .fillMaxHeight()
                    .background(Tokens.SurfaceVariant)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            DrawerHeader(onStartNew = onStartNew, onClose = onDismiss)
            if (sessions.isEmpty()) {
                EmptySessions()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 4.dp,
                            bottom = 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = sessions, key = { it.id }) { session ->
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            onOpen = { onOpen(session.id) },
                            onDelete = { onDelete(session.id) },
                        )
                    }
                }
            }
        }
        // 右侧遮罩：点一下就关，不需要额外找关闭按钮
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun DrawerHeader(
    onStartNew: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "对话",
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "收起会话列表",
                    tint = Tokens.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 「新建对话」做成整行按钮：这是最常用的动作，不该藏进图标里
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.RadiusMedium))
                    .background(Tokens.Surface)
                    .clickable(onClick = onStartNew)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = Tokens.Primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "新建对话",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.Primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: SolveSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(if (selected) Tokens.PrimaryContainer else Color.Transparent)
                .clickable(onClick = onOpen)
                .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Tokens.Primary else Tokens.OnSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitleOf(session),
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除这条对话",
                tint = Tokens.OnSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** 会话副标题：科目 · 轮次，缺失时退回轮次或归档标记。 */
private fun subtitleOf(session: SolveSession): String {
    if (session.subtitle.isNotBlank()) return session.subtitle
    if (session.archived) return "已结束 · " + session.roundCount + " 轮"
    return session.roundCount.toString() + " 轮对话"
}

@Composable
private fun EmptySessions() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "还没有对话",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurface,
        )
        Text(
            text = "拍一道题、问一句，这里就会留下记录，随时可以回来翻。",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.OnSurfaceVariant,
        )
    }
}

/** 侧边栏宽度：屏宽的 78%，但不超过 320dp，保证窄屏上仍能看到右侧内容。 */
private val DRAWER_WIDTH = 300.dp
