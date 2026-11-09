package com.totop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/**
 * 统一卡片（白底、12dp 圆角、14dp 内边距）。
 *
 * 从报告页里那份 private 实现提出来，供学情页与报告页共用：
 * 两份实现一旦分叉，两个页面的卡片内边距与标题字号就会慢慢不一样。
 *
 * @param title 标题；为空时不画标题行
 * @param trailing 标题行右侧的附加内容（筛选入口、计数等）
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(Tokens.Surface, RoundedCornerShape(Tokens.RadiusMedium))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Tokens.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
        }
        content()
    }
}
