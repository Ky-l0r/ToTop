package com.totop.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.totop.app.ui.theme.Tokens

/**
 * 统一样式的卡片容器。
 *
 * 全应用只留两档观感（见 [CardTone]），圆角与内边距固定在 [Tokens.RadiusMedium]
 * 与 [Tokens.CardPadding]：页面不再自己拼 `background` 加圆角——那正是以前观感漂移的来源。
 *
 * @param onClick 传入即为可点卡片（带涟漪与无障碍语义）；为空时是纯容器
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Raised,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Tokens.RadiusMedium)
    val colors =
        CardDefaults.cardColors(
            containerColor = if (tone == CardTone.Raised) Tokens.Surface else Tokens.SurfaceVariant,
        )
    val elevation =
        CardDefaults.cardElevation(
            defaultElevation = if (tone == CardTone.Raised) Tokens.Elevation else 0.dp,
        )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
        ) {
            CardBody(content)
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
        ) {
            CardBody(content)
        }
    }
}

/** 卡片内容区：统一内边距与纵向排布，两档底色共用。 */
@Composable
private fun CardBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.CardPadding, vertical = Tokens.CardPadding),
        content = content,
    )
}
