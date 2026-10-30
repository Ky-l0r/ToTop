package com.totop.app.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.totop.app.domain.model.SubjectScope
import com.totop.app.domain.model.Subjects
import com.totop.app.ui.theme.Tokens

/**
 * 选科目。
 *
 * 两个入口共用一个页面：
 * - 首次启动（引导之后）：没有返回键，必须选完才能进首页；
 * - 设置 → 学习的科目：有返回键，随时可改。
 *
 * 为什么第一次就要问：
 * 十科的错题本与知识点页对新用户来说全是空的分组，一眼看不到自己那一科；
 * 先问一次，之后所有列表只出现在学的科目里，首页统计的分母也跟着变小。
 *
 * @param onDone 保存成功后的收尾（首次启动去首页，设置里回上一页）
 * @param onBack 为 null 表示这是首次启动流程，不提供返回
 */
@Composable
fun SubjectPickerScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: SubjectPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Tokens.Background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Tokens.OnSurface,
                    )
                }
            }
            Text(
                text = "你学哪几科",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Tokens.OnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::toggleAll) {
                Text(
                    text = if (state.allSelected) "全不选" else "全选",
                    style = MaterialTheme.typography.labelLarge,
                    color = Tokens.Primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "可以多选，也可以全选。错题本和知识点只显示你选的科目，随时能在设置里改。",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.OnSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // 两列网格：十科用一列要滚很久，用 FlowRow 又要吃实验性 API
        // （Compose 1.5 里还是 @ExperimentalLayoutApi），两列手排最稳。
        val rows = SubjectScope.PICKABLE.chunked(2)
        rows.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { code ->
                    SubjectOption(
                        code = code,
                        checked = state.selected.contains(code),
                        onToggle = { viewModel.toggle(code) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 单数行的占位：不补会让最后一科的格子被拉成两倍宽
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.save(onDone) },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text =
                    if (state.selected.isEmpty()) {
                        "至少选一科"
                    } else {
                        "就这些（" + state.selected.size + " 科）"
                    },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "没选的科目不会消失，只是先不显示；之后在设置里勾回来就都能看到。",
            style = MaterialTheme.typography.labelSmall,
            color = Tokens.OnSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 一个科目格子：整块可点，选中态用主色底 + 勾。 */
@Composable
private fun SubjectOption(
    code: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(Tokens.RadiusMedium))
                .background(if (checked) Tokens.PrimaryContainer else Tokens.Surface)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) Tokens.Primary else Tokens.SurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = Subjects.label(code),
            style = MaterialTheme.typography.bodyLarge,
            color = if (checked) Tokens.Primary else Tokens.OnSurface,
        )
    }
}
