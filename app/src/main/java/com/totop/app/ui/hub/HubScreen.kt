package com.totop.app.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/** 功能中心的一个入口。 */
data class HubEntry(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/**
 * 功能中心：承接原底部导航里的低频入口。
 *
 * 设计原则：
 * - 按「我常做什么」分组，而不是按代码模块分组；
 * - 每条都有副标题解释用途，学生不需要猜；
 * - 设置类入口放在最后，避免干扰主流程。
 */
@Composable
fun HubScreen(
    onOpenMistakes: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenReviewMaterial: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCompanion: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val groups =
        listOf(
            "学习" to
                listOf(
                    HubEntry("错题本", "按科目和知识点翻看做过错的题", onOpenMistakes),
                    HubEntry("还需要多练的知识点", "看看哪些地方反复出错", onOpenKnowledge),
                    HubEntry("复习打卡", "安排今天该复习的题", onOpenReview),
                    HubEntry("陪伴作业", "写作业时用：每题先自己想五分钟", onOpenCompanion),
                ),
            "考前冲刺" to
                listOf(
                    HubEntry("针对性复习资料", "根据最近错题生成，可打印", onOpenReviewMaterial),
                    HubEntry("离线记题", "没网时先拍下来，联网后再解", onOpenOffline),
                ),
            "了解自己" to
                listOf(
                    HubEntry("学情报告", "按科目看最近学得怎么样", onOpenReport),
                ),
            "其他" to
                listOf(
                    HubEntry("设置", "模型连接、拍照、导出与打印", onOpenSettings),
                ),
        )

    Column(
        modifier =
            modifier.fillMaxSize().background(Tokens.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "全部功能",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Tokens.OnSurface,
        )
        Text(
            text = "常用的都在这里，按你想做的事分组。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )

        groups.forEach { (groupTitle, entries) ->
            Spacer(Modifier.height(20.dp))
            Text(
                text = groupTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Tokens.OnSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            entries.forEach { entry ->
                Card(
                    onClick = entry.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Tokens.RadiusMedium),
                    colors = CardDefaults.cardColors(containerColor = Tokens.SurfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Tokens.OnSurface,
                            )
                            Text(
                                text = entry.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Tokens.OnSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = Tokens.OutlineStrong,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
