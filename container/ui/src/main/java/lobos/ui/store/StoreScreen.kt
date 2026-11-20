package lobos.ui.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.components.LobosCard
import lobos.ui.theme.LobosColors
import lobos.ui.theme.LobosTheme

@Composable
fun StoreScreen(
    state: StoreUiState,
    onEvent: (StoreEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            StoreUiState.Loading -> LoadingContent()
            is StoreUiState.Ready -> ReadyContent(state, onEvent)
            is StoreUiState.Error -> ErrorContent(state.message, onEvent)
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ReadyContent(state: StoreUiState.Ready, onEvent: (StoreEvent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全部应用",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                RefreshIconButton(
                    refreshing = state.refreshing,
                    onClick = { onEvent(StoreEvent.Refresh) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        if (state.apps.isEmpty()) {
            item { EmptyContent(onRefresh = { onEvent(StoreEvent.Refresh) }) }
        } else {
            items(state.apps, key = { it.name }) { app ->
                StoreAppCard(app = app, onClick = { onEvent(StoreEvent.OpenDetail(app.name)) })
            }
        }
    }
}

@Composable
private fun RefreshIconButton(refreshing: Boolean, onClick: () -> Unit) {
    if (refreshing) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .padding(12.dp),
            strokeWidth = 2.dp,
        )
    } else {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "刷新应用列表",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StoreAppCard(app: StoreAppItem, onClick: () -> Unit) {
    LobosCard(
        title = app.name,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusIcon = if (app.installed) Icons.Filled.CheckCircle else Icons.Outlined.FileDownload
            val statusTint = if (app.installed) {
                LobosColors.success
            } else {
                MaterialTheme.colorScheme.primary
            }
            val statusDescription = if (app.installed) "已安装" else "未安装"
            Icon(
                imageVector = statusIcon,
                contentDescription = statusDescription,
                tint = statusTint,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${app.versionLabel}  ·  ${app.stateLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyContent(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有取到应用清单",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) {
            Text("刷新")
        }
    }
}

@Composable
private fun ErrorContent(message: String, onEvent: (StoreEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onEvent(StoreEvent.Refresh) }) {
            Text("重试")
        }
    }
}

private val previewApps = listOf(
    StoreAppItem("python", "3.12.1", "可更新", installed = true),
    StoreAppItem("nodejs", "22.4.0", "未安装", installed = false),
    StoreAppItem("curl", "8.9.0", "已安装", installed = true),
)

@Preview(showBackground = true)
@Composable
private fun StoreLoadingPreview() {
    LobosTheme(dynamicColor = false) {
        StoreScreen(state = StoreUiState.Loading, onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun StoreReadyPreview() {
    LobosTheme(dynamicColor = false) {
        StoreScreen(state = StoreUiState.Ready(previewApps), onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun StoreReadyEmptyPreview() {
    LobosTheme(dynamicColor = false) {
        StoreScreen(state = StoreUiState.Ready(emptyList()), onEvent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun StoreErrorPreview() {
    LobosTheme(dynamicColor = false) {
        StoreScreen(state = StoreUiState.Error("获取应用列表失败"), onEvent = {})
    }
}
