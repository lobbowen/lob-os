package lobos.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import lobos.ui.theme.LobosTheme

@Composable
fun LobosCard(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cardModifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (onClick != null) {
        Card(modifier = cardModifier, onClick = onClick) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        Card(modifier = cardModifier) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LobosPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Composable
fun LobosOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Preview(showBackground = true)
@Composable
private fun LobosComponentsPreview() {
    LobosTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp)) {
            LobosCard("python", "3 个版本可选", onClick = {})
            LobosPrimaryButton("安装", onClick = {})
            LobosOutlineButton("切换到 3.11.13", onClick = {})
        }
    }
}
