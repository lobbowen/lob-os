package lobos.ui.apps

import lobos.ui.theme.Spacing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosTheme

@Composable
fun AppsScreen(
    state: AppsUiState,
    onEvent: (AppsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state) {
            is AppsUiState.Ready -> AppsReady(state, onEvent)
            is AppsUiState.Error -> AppsError(state.msg, onEvent)
        }
    }
}

@Composable
private fun AppsReady(state: AppsUiState.Ready, onEvent: (AppsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
    ) {
        Text("应用", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Spacer(Modifier.height(Spacing.sm))
        Text("开发者社区提供的应用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xl))
        if (state.apps.isEmpty()) {
            EmptyApps(onEvent)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(state.apps, key = { it }) { app ->
                    AppCard(app)
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = { onEvent(AppsEvent.AddApp) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.giant),
            shape = RoundedCornerShape(Spacing.lg),
        ) {
            Text("+ 添加应用", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EmptyApps(onEvent: (AppsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val emptyShape = RoundedCornerShape(Spacing.xxl)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.CenterVertically)
            .clip(emptyShape)
            .background(cs.surface.copy(alpha = 0.5f))
            .border(1.5.dp, cs.outlineVariant, emptyShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxxl, vertical = Spacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(Spacing.giant)
                    .clip(RoundedCornerShape(Spacing.xl))
                    .background(cs.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.xxxl),
                    tint = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            Text("还没有应用", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "应用由开发者社区创建并安装到系统，系统建设完成后，应用将在这里出现",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AppCard(name: String) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.lg),
        shape = RoundedCornerShape(Spacing.xl),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = Spacing.xs),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(Spacing.huge).clip(RoundedCornerShape(Spacing.md)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = cs.primary, modifier = Modifier.size(Spacing.xl))
            }
            Spacer(Modifier.height(Spacing.md))
            Text(name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AppsError(message: String, onEvent: (AppsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
    ) {
        Text("应用", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Spacer(Modifier.height(Spacing.sm))
        Text("开发者社区提供的应用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.error,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = { onEvent(AppsEvent.AddApp) },
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.giant),
            shape = RoundedCornerShape(Spacing.lg),
        ) {
            Text("+ 添加应用", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppsEmptyPreview() {
    LobosTheme(dynamicColor = false) {
        AppsScreen(state = AppsUiState.Ready(emptyList()), onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AppsReadyPreview() {
    LobosTheme(dynamicColor = false) {
        AppsScreen(
            state = AppsUiState.Ready(listOf("Markdown 阅读器", "日志查看器", "进程面板", "文件便签")),
            onEvent = {},
        )
    }
}
