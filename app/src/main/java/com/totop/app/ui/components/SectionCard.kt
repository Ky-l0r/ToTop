package com.totop.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.totop.app.ui.theme.Tokens

/**
 * 带标题的内容卡片（白底、12dp 圆角、14dp 内边距、内容间距 8dp）。
 *
 * 从报告页里那份 private 实现提出来，供学情页与报告页共用。
 * 2026-11 起内部改为基于 [SurfaceCard]，与首页、资料库走同一个组件：
 * 以前它是「白底无边界」，在同样白底的页面上等于没有卡片；现在有一层 1dp 阴影把边界交代清楚。
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
    SurfaceCard(modifier = modifier, tone = CardTone.Raised, spacing = Tokens.SpaceSm) {
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
