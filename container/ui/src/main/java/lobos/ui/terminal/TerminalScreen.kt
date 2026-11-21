package lobos.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TerminalBackground = Color(0xFF141821)
private val TerminalBorder = Color(0xFF2A303C)
private val TerminalDivider = Color(0xFF242A35)
private val TerminalMuted = Color(0xFF8B93A1)
private val TerminalText = Color(0xFFE6EAF2)

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onEvent: (TerminalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color(0xFFF7F8FA))) {
        when (state) {
            is TerminalUiState.Ready -> TerminalReady(state, onEvent)
            is TerminalUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.msg, color = Color(0xFFFF6B6B))
            }
        }
    }
}

@Composable
private fun TerminalReady(state: TerminalUiState.Ready, onEvent: (TerminalEvent) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("终端", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge, color = Color(0xFF101216))
        Spacer(Modifier.height(5.dp))
        Text(
            if (state.connected) "在 LobOS 中运行命令" else "会话已断开",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = Color(0xFF8B929D),
        )
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier.fillMaxWidth().weight(1f).clip(shape).background(TerminalBackground)
                .border(1.dp, TerminalBorder, shape)
                .clickable { },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { c ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.size(7.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("bash · aarch64", color = TerminalMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TerminalDivider))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
            ) {
                itemsIndexed(state.screen, key = { i, _ -> i }) { _, line ->
                    Text(
                        text = renderLine(line),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TerminalDivider))
            TextField(
                value = input,
                onValueChange = { v ->
                    if (v.length > input.length) {
                        val added = v.drop(input.length)
                        onEvent(TerminalEvent.Input(added))
                    } else if (v.length < input.length) {
                        repeat(input.length - v.length) { onEvent(TerminalEvent.Input("\b")) }
                    }
                    input = ""
                },
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                singleLine = true,
                textStyle = TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onEvent(TerminalEvent.Input("\n")); input = "" },
                    onSend = { onEvent(TerminalEvent.Input("\n")); input = "" },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Color.Transparent,
                    focusedTextColor = Color.Transparent,
                    unfocusedTextColor = Color.Transparent,
                ),
                placeholder = { Text("点此输入命令", color = Color(0xFF4A5260), fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            )
        }
    }
}

private fun renderLine(line: TerminalLine): AnnotatedString = buildAnnotatedString {
    line.chars.forEach { sc ->
        val style = SpanStyle(
            color = sc.color?.let { Color(it) } ?: TerminalText,
            fontWeight = if (sc.bold) FontWeight.Bold else FontWeight.Normal,
        )
        withStyle(style) { append(sc.c) }
    }
}
