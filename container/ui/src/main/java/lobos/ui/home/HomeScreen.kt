package lobos.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import lobos.ui.components.UiState
import lobos.ui.theme.LobosTheme

data class HomeMetric(val value: String, val label: String)

sealed interface HomeUiState : UiState {
    data class Ready(
        val running: Boolean,
        val uptime: String,
        val online: Boolean,
        val metrics: List<HomeMetric>,
        val apps: List<String>,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

sealed interface HomeEvent {
    data object TogglePower : HomeEvent
    data class OpenEntry(val key: String) : HomeEvent
    data object AddApp : HomeEvent
}

private val entries = listOf(
    Triple("terminal", "终端", Icons.Filled.Terminal),
    Triple("components", "组件", Icons.Filled.Dataset),
    Triple("space", "空间", Icons.Filled.Storage),
    Triple("apps", "应用", Icons.Filled.Apps),
    Triple("more", "更多", Icons.Filled.MoreHoriz),
)

@Composable
fun HomeScreen(state: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is HomeUiState.Error -> Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        is HomeUiState.Ready -> HomeReady(state, onEvent, modifier)
    }
}

@Composable
private fun HomeReady(state: HomeUiState.Ready, onEvent: (HomeEvent) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val panelShape = RoundedCornerShape(30.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, panelShape)
                .clip(panelShape)
                .background(cs.surface)
                .border(1.dp, cs.outlineVariant, panelShape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(cs.onSurface),
                    contentAlignment = Alignment.Center) {
                    Text("L", color = cs.surface, fontWeight = FontWeight(750), fontSize = 16.sp)
                }
                Spacer(Modifier.size(9.dp))
                Text("LOBOS", fontWeight = FontWeight(700), color = cs.onSurface,
                    fontSize = 13.sp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(22.dp)).background(cs.onSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFF5EE08A)))
                    Spacer(Modifier.size(7.dp))
                    Text(if (state.online) "在线" else "离线", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight(600))
                }
                Spacer(Modifier.size(9.dp))
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).border(1.dp, cs.outline)
                    .clickable { onEvent(HomeEvent.TogglePower) }, contentAlignment = Alignment.Center) {
                    Text("⏻", color = cs.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(16.dp))
                Text("SYSTEM STATUS", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text(if (state.running) "运行中" else "已停止", style = MaterialTheme.typography.displayLarge, color = cs.onSurface)
                Text("控制面在线 · 已稳定运行 ${state.uptime}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                    state.metrics.forEachIndexed { i, m ->
                        if (i > 0) Box(Modifier.width(1.dp).height(34.dp).background(cs.outlineVariant))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(m.value, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                            Spacer(Modifier.size(5.dp))
                            Text(m.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(86.dp), verticalAlignment = Alignment.CenterVertically) {
                entries.forEach { (key, label, icon) ->
                    Column(
                        Modifier.weight(1f).height(86.dp).clickable { onEvent(HomeEvent.OpenEntry(key)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, contentDescription = label, modifier = Modifier.size(27.dp), tint = cs.onSurface)
                        Spacer(Modifier.size(8.dp))
                        Text(label, color = cs.onSurface, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("快速启动", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
                Text("添加应用后，可在这里直接启动", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(22.dp))
                .border(1.5.dp, cs.outlineVariant, RoundedCornerShape(22.dp))
                .background(cs.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(cs.surfaceVariant),
                    contentAlignment = Alignment.Center) {
                    Text("+", color = cs.onSurfaceVariant, fontSize = 30.sp, fontWeight = FontWeight(300))
                }
                Spacer(Modifier.size(11.dp))
                Text("还没有应用", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                Text("开发者社区的应用安装后会出现在这里", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    LobosTheme {
        HomeScreen(
            HomeUiState.Ready(
                running = true, uptime = "6h 12m", online = true,
                metrics = listOf(
                    HomeMetric("12%", "内存"), HomeMetric("3", "运行时版本"),
                    HomeMetric("10", "已装组件"), HomeMetric("0", "活跃实例"),
                ),
                apps = emptyList(),
            ),
            {},
        )
    }
}
