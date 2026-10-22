package com.totop.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.theme.Tokens

/** 「全部」的固定标识，避免与真实科目代码混淆。 */
const val ALL_SUBJECTS: String = "__all__"

/**
 * 科目筛选条。
 *
 * 设计要点：
 * - 第一项固定是「全部」，默认选中，用户不操作也能看到汇总；
 * - **没有数据的科目置灰并显示「暂无记录」，而不是直接隐藏**：
 *   隐藏会让用户以为功能缺失，置灰能传达「以后会有」；
 * - 横向可滚动，科目多时不换行、不挤压。
 *
 * @param available 有数据的科目代码集合（来自数据库聚合）
 * @param selected 当前选中项（科目代码或 [ALL_SUBJECTS]）
 */
@Composable
fun SubjectTabs(
    available: Set<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showAll: Boolean = true,
) {
    val codes =
        buildList {
            if (showAll) add(ALL_SUBJECTS)
            addAll(Subjects.ORDER)
        }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        codes.forEach { code ->
            val isAll = code == ALL_SUBJECTS
            val label = if (isAll) "全部" else Subjects.label(code)
            val hasData = isAll || available.contains(code)
            SubjectChip(
                label = label,
                selected = code == selected,
                enabled = hasData,
                onClick = { if (hasData) onSelect(code) },
            )
        }
    }
}

@Composable
private fun SubjectChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background =
        when {
            selected -> Tokens.Primary
            enabled -> Tokens.SurfaceVariant
            else -> Tokens.SurfaceVariant.copy(alpha = 0.5f)
        }
    val textColor =
        when {
            selected -> Color.White
            enabled -> Tokens.OnSurface
            else -> Tokens.OnSurfaceVariant.copy(alpha = 0.55f)
        }
    Text(
        text = if (enabled) label else label + "（暂无）",
        style = MaterialTheme.typography.labelLarge,
        color = textColor,
        modifier =
            Modifier
                .background(background, RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
