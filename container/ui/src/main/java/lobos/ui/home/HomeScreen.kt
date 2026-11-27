package lobos.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lobos.ui.R
import lobos.ui.components.UiState
import lobos.ui.theme.LobosColors

data class HomeMetric(val value: String, val label: String)

sealed interface HomeUiState : UiState {
    data class Ready(
        val running: Boolean,
        val uptimeMinutes: Long,
        val online: Boolean,
        val metrics: List<HomeMetric>,
        val apps: List<String>,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

sealed interface HomeEvent

/** Home screen: running badge + big uptime, one integrated overview card, quick launch. */
@Composable
fun HomeScreen(state: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is HomeUiState.Error -> Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is HomeUiState.Ready -> HomeReady(state, modifier)
    }
}

@Composable
private fun HomeReady(state: HomeUiState.Ready, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(8.dp)
    val statusText = if (state.running) stringResource(R.string.home_running) else stringResource(R.string.home_stopped)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        // Top: running badge on the left, big uptime on the right
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(cs.onSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(LobosColors.success))
                Spacer(Modifier.size(7.dp))
                Text(statusText, color = cs.surface, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            val hours = state.uptimeMinutes / 60
            val mins = state.uptimeMinutes % 60
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hours.toString(), color = cs.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
                Text(stringResource(R.string.unit_hour), color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(horizontal = 2.dp))
                Spacer(Modifier.size(4.dp))
                Text(mins.toString().padStart(2, '0'), color = cs.onSurface, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
                Text(stringResource(R.string.unit_minute), color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Normal, modifier = Modifier.padding(start = 2.dp))
            }
        }

        // One integrated overview card: four stats separated by thin dividers, no title
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).height(64.dp)
                .clip(cardShape).background(cs.surface)
                .border(1.dp, cs.outlineVariant, cardShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.metrics.take(4).forEachIndexed { i, m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(m.value, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(localizedLabel(m.label), color = cs.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
                }
                if (i < minOf(state.metrics.size, 4) - 1) {
                    Box(Modifier.width(1.dp).height(28.dp).background(cs.outlineVariant))
                }
            }
        }

        // Quick launch
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.home_quick_launch), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.home_quick_hint), color = cs.onSurfaceVariant, fontSize = 10.sp)
        }
        Box(
            Modifier.fillMaxWidth().height(100.dp).padding(top = 9.dp)
                .clip(cardShape).background(cs.surface)
                .border(1.dp, cs.outlineVariant, cardShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state.apps.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text("+", color = cs.onSurfaceVariant, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(stringResource(R.string.home_no_apps), color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.home_no_apps_hint), color = cs.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}

/** Map ViewModel metric labels to string resources so UI is fully localized. */
@Composable
private fun localizedLabel(label: String): String = when (label) {
    "内存占用", "Memory" -> stringResource(R.string.home_metric_memory)
    "线程", "Threads" -> stringResource(R.string.home_metric_threads)
    "活跃会话", "Sessions" -> stringResource(R.string.home_metric_sessions)
    "系统盘", "就绪", "未就绪", "应用程序", "Apps" -> stringResource(R.string.home_metric_apps)
    else -> label
}
