package com.totop.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/**
 * 「怎么填模型配置」的示意图与分步说明。
 *
 * 为什么用示意图而不是截图：
 * 截图要看具体机型的设置页长什么样，一旦改版就过期；示意图只表达「哪三个框、按钮在哪」，
 * 这几件事不会变。真实截图还需要打码 Key，放在引导里反而更容易让人以为要照着输一个现成的 Key。
 *
 * 图里刻意**不出现任何真实 Key**：只画输入框的形状与顺序，避免被误当成示例值照抄。
 */
@Composable
fun ApiSetupGuide(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ApiSetupIllustration()
        GUIDE_STEPS.forEachIndexed { index, step ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Tokens.PrimaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Tokens.Primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.OnSurfaceVariant,
                )
            }
        }
        Text(
            text = "截图分享给别人时，记得把 Key 那一行打码。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.Warning,
        )
    }
}

/**
 * 设置页的示意图：一张卡片，三个输入框 + 一个校验按钮。
 *
 * 全部用 Compose 基础形状画（圆角矩形 + 描边），不引入图片资源：
 * 这样在深色/不同密度下都不会糊，也不会为一张示意图多出几份 PNG。
 */
@Composable
private fun ApiSetupIllustration(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Tokens.PrimaryContainer)
                .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "模型设置",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Tokens.OnPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Tokens.Success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "连接正常",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.Success,
                )
            }
            FIELD_LABELS.forEach { label ->
                FauxField(label = label)
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Tokens.Primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "校验连通性",
                    style = MaterialTheme.typography.labelMedium,
                    color = Tokens.Surface,
                )
            }
        }
    }
}

/** 一行假输入框：只有标签与空槽，不出现任何示例值。 */
@Composable
private fun FauxField(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnPrimaryContainer.copy(alpha = 0.8f),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Outline, RoundedCornerShape(8.dp)),
        ) {
            // 槽里留一条灰线示意「这里要填东西」，不放任何文字
            Box(
                modifier =
                    Modifier
                        .padding(start = 8.dp, top = 12.dp)
                        .width(56.dp)
                        .height(2.dp)
                        .background(Tokens.Outline),
            )
        }
    }
}

/** 三个必填项，顺序与设置页一致。 */
private val FIELD_LABELS: List<String> = listOf("Base URL", "API Key", "模型名")

/** 分步说明：从「去哪拿」到「怎么确认能用」。 */
private val GUIDE_STEPS: List<String> =
    listOf(
        "到你用的模型厂商控制台（如阿里云百炼、DeepSeek、Moonshot）新建一个 API Key。",
        "复制两样东西：Base URL（形如 https://xxx/v1）与模型名（形如 qwen-vl-plus）。",
        "回到本应用：设置 → 模型与连接，三行依次粘贴；Key 只会加密存在本机。",
        "点「校验连通性」，看到「连接正常」就可以去拍题了。",
    )
