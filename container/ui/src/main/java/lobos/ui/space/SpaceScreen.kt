package lobos.ui.space

import lobos.ui.theme.Spacing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosTheme

@Composable
fun SpaceScreen(state: SpaceUiState, onEvent: (SpaceEvent) -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is SpaceUiState.Error -> Box(modifier.fillMaxWidth().height(Spacing.lg), contentAlignment = Alignment.Center) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is SpaceUiState.Ready -> SpaceReady(state, onEvent, modifier)
    }
}

@Composable
private fun SpaceReady(state: SpaceUiState.Ready, onEvent: (SpaceEvent) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.xl)) {
        Spacer(Modifier.height(Spacing.sm))
        Text("空间", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Text("系统文件管理", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

        Spacer(Modifier.height(Spacing.lg))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Spacing.xl)).background(cs.surface)
                .shadow(Spacing.sm, RoundedCornerShape(Spacing.xl)).padding(Spacing.xl),
        ) {
            Text("已占用", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.used, style = MaterialTheme.typography.displayLarge, color = cs.onSurface)
            }
            Spacer(Modifier.height(Spacing.lg))
            LinearProgressIndicator(
                progress = { state.usedRatio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(Spacing.md).clip(RoundedCornerShape(Spacing.sm)),
                color = cs.primary,
                trackColor = cs.surfaceVariant,
            )
            Spacer(Modifier.height(Spacing.lg))
            Row {
                Text("已用 ${state.used}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("可用 ${state.free}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("总计 ${state.total}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(state.path, style = MaterialTheme.typography.bodySmall, color = cs.primary)

        Spacer(Modifier.height(Spacing.sm))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Spacing.xl)).background(cs.surface)
            .shadow(Spacing.sm, RoundedCornerShape(Spacing.xl))) {
            state.dirs.forEachIndexed { i, d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEvent(SpaceEvent.OpenDir(d.name)) }
                        .padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(Spacing.huge).clip(RoundedCornerShape(Spacing.sm)).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(Spacing.xl), tint = cs.primary)
                    }
                    Spacer(Modifier.size(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                        Text(d.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text(d.size, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (i < state.dirs.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = Spacing.lg).background(cs.outlineVariant))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpacePreview() {
    LobosTheme {
        SpaceScreen(
            SpaceUiState.Ready(
                used = "603.3 MB", free = "364 GB", total = "365 GB", usedRatio = 0.38f,
                path = "/data/user/0/lobos.os/files",
                dirs = listOf(
                    DirItem("opt", "已装组件与运行时", "534 MB"),
                    DirItem("usr", "系统入口与共享库", "48 MB"),
                    DirItem("var", "状态 · 日志 · 清单", "21 MB"),
                    DirItem("cache", "临时文件", "<1 MB"),
                ),
            ),
            {},
        )
    }
}
