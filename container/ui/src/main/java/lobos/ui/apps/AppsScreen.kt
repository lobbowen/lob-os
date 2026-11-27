package lobos.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lobos.ui.R

/** App market: search + recommended list with install / installed states. */
@Composable
fun AppsScreen(state: AppsUiState, onEvent: (AppsEvent) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(8.dp)),
            placeholder = { Text(stringResource(R.string.apps_search), fontSize = 11.sp, color = cs.onSurfaceVariant) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surface, unfocusedContainerColor = cs.surface,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.apps_recommended), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        when (state) {
            is AppsUiState.Error -> Text(state.msg, color = cs.error)
            is AppsUiState.Ready -> {
                val list = state.apps.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                LazyColumn {
                    items(list, key = { it.id }) { app -> AppRow(app, onEvent) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, onEvent: (AppsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(64.dp)
            .clip(shape).background(cs.surface).border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(Color(app.color)), contentAlignment = Alignment.Center) {
            Text(app.symbol, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text("${app.category} · ${app.size}", color = cs.onSurfaceVariant, fontSize = 9.sp)
        }
        if (app.installed) {
            Text(
                stringResource(R.string.apps_installed), color = cs.primary, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, cs.primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        } else {
            Text(
                stringResource(R.string.apps_install), color = cs.onPrimary, fontSize = 10.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primary)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
