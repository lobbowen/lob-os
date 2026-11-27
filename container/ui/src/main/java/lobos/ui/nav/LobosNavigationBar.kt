package lobos.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lobos.ui.R

/** Bottom tab identifier. Mirrors the app's top-level destinations. */
enum class LobosTab(
    val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
) {
    Home(R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Terminal(R.string.nav_terminal, Icons.Outlined.Terminal, Icons.Filled.Terminal),
    Space(R.string.nav_space, Icons.Outlined.Storage, Icons.Filled.Storage),
    Apps(R.string.nav_apps, Icons.Outlined.Apps, Icons.Filled.Apps),
    More(R.string.nav_more, Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
}

/**
 * A single rounded "tray" bottom navigation with one pill-highlighted selection.
 * Built on standard Compose selectable semantics (role=Tab, selected, 48dp touch)
 * while drawing the capsule tray with Material3 colors. Adapted to window insets
 * by the caller (Scaffold bottomBar uses navigationBarsPadding here).
 */
@Composable
fun LobosNavigationBar(
    selected: LobosTab,
    onSelect: (LobosTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val trayShape = RoundedCornerShape(30.dp)
    val itemShape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(10.dp, trayShape)
                .clip(trayShape)
                .background(cs.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LobosTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val label = stringResource(tab.labelRes)
                val bg = if (isSelected) cs.primary else androidx.compose.ui.graphics.Color.Transparent
                val fg = if (isSelected) cs.onPrimary else cs.onSurfaceVariant
                ColumnItem(
                    icon = if (isSelected) tab.filled else tab.outlined,
                    label = label,
                    selected = isSelected,
                    colorBg = bg,
                    colorFg = fg,
                    shape = itemShape,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    colorBg: androidx.compose.ui.graphics.Color,
    colorFg: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(colorBg)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics {
                this.selected = selected
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colorFg)
        Text(label, color = colorFg, fontSize = 10.sp, maxLines = 1)
    }
}

