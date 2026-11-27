package lobos.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lobos.ui.R

/** Space screen: a file manager over the system disk. */
@Composable
fun SpaceScreen(state: SpaceUiState, onEvent: (SpaceEvent) -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is SpaceUiState.Error -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is SpaceUiState.Ready -> SpaceReady(state, onEvent, modifier)
    }
}

@Composable
private fun SpaceReady(state: SpaceUiState.Ready, onEvent: (SpaceEvent) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        // Storage overview: compact, no heavy shadow
        Column(Modifier.fillMaxWidth().clip(shape).background(cs.surface).padding(14.dp)) {
            Text(stringResource(R.string.space_used), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(state.used, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.usedRatio.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = cs.primary, trackColor = cs.surfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text("${stringResource(R.string.space_used)} ${state.used}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${stringResource(R.string.space_free)} ${state.free}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${stringResource(R.string.space_total)} ${state.total}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))
        // Toolbar: back + breadcrumb + refresh + item count
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onEvent(SpaceEvent.NavigateUp) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up", modifier = Modifier.size(18.dp), tint = cs.onSurface)
            }
            Text(
                if (state.breadcrumb.isEmpty()) stringResource(R.string.space_root) else state.breadcrumb.joinToString(" / "),
                style = MaterialTheme.typography.titleSmall, color = cs.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.space_items, state.entryCount), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            IconButton(onClick = { onEvent(SpaceEvent.Refresh) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = cs.outlineVariant)

        // File list
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.entries, key = { it.name }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = e.isDir) { onEvent(SpaceEvent.OpenDir(e.name)) }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(if (e.isDir) cs.primaryContainer else cs.surfaceVariant),
                        contentAlignment = Alignment.Center) {
                        Icon(
                            if (e.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = if (e.isDir) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${if (e.isDir) stringResource(R.string.space_folder) else e.kind} · ${e.size}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    Text(e.modified, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                HorizontalDivider(color = cs.outlineVariant)
            }
        }
    }
}
