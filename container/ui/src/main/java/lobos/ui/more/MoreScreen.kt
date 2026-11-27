package lobos.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosTheme

private data class MoreItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private data class MoreGroup(val title: String, val items: List<MoreItem>)

private val groups = listOf(
    MoreGroup(
        title = "电源",
        items = listOf(
            MoreItem("shutdown", "关机", "停止内核运行时，管理界面保留", Icons.Filled.PowerSettingsNew),
            MoreItem("boot", "开机", "启动内核运行时，恢复程序", Icons.Filled.PlayArrow),
            MoreItem("reboot", "重启", "关机后立即开机", Icons.Filled.RestartAlt),
        ),
    ),
    MoreGroup(
        title = "系统",
        items = listOf(
            MoreItem("about", "关于 LobOS", "版本、设备与系统身份", Icons.Filled.Info),
            MoreItem("check", "系统体检", "检查系统组件与运行状态", Icons.Filled.MonitorHeart),
            MoreItem("diagnosis", "故障诊断", "查看故障记录并进入修复", Icons.Filled.Build),
        ),
    ),
    MoreGroup(
        title = "能力",
        items = listOf(
            MoreItem("api", "能力 API", "能力与接口说明", Icons.Filled.Api),
            MoreItem("runtime", "运行时状态", "运行时与活跃实例概况", Icons.Filled.Storage),
        ),
    ),
)

@Composable
fun MoreScreen(
    state: MoreUiState,
    onEvent: (MoreEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            groups.forEach { group ->
                item(key = group.title) {
                    Text(group.title, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(9.dp))
                    GroupCard(group.items, onEvent)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(items: List<MoreItem>, onEvent: (MoreEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(cs.outlineVariant))
            }
            MoreRow(item = item, onClick = { onEvent(MoreEvent.Open(item.key)) })
        }
    }
}

@Composable
private fun MoreRow(item: MoreItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).background(cs.primaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.size(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MorePreview() {
    LobosTheme(dynamicColor = false) {
        MoreScreen(state = MoreUiState.Ready, onEvent = {})
    }
}
