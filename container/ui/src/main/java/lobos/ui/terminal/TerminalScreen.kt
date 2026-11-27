package lobos.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lobos.ui.theme.LobosColors
import lobos.ui.theme.MonoTextStyle

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onEvent: (TerminalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(cs.background)) {
        when (state) {
            is TerminalUiState.Ready -> TerminalReady(state, onEvent)
            is TerminalUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.msg, color = cs.error)
            }
        }
    }
}

@Composable
private fun TerminalReady(state: TerminalUiState.Ready, onEvent: (TerminalEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pal = LobosColors.current
    val shape = RoundedCornerShape(14.dp)
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var lastValue by remember { mutableStateOf("") }

    fun keyName(code: Int): String? = when (code) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> "UP"
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> "HOME"
        AndroidKeyEvent.KEYCODE_MOVE_END -> "END"
        AndroidKeyEvent.KEYCODE_INSERT -> "INSERT"
        AndroidKeyEvent.KEYCODE_DEL -> "BACKSPACE"
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "DELETE"
        AndroidKeyEvent.KEYCODE_PAGE_UP -> "PAGE_UP"
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> "PAGE_DOWN"
        AndroidKeyEvent.KEYCODE_TAB -> "TAB"
        AndroidKeyEvent.KEYCODE_ESCAPE -> "ESCAPE"
        AndroidKeyEvent.KEYCODE_ENTER -> "ENTER"
        else -> null
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp, vertical = 14.dp)) {

        // 输出区：VT 全部 scrollback + 屏幕，等宽逐行，自动跟随底部
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(shape).background(pal.terminalBg)
                .border(1.dp, pal.terminalBorder, shape),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { c ->
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Spacer(Modifier.size(6.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("bash · aarch64", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = pal.terminalMuted)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.terminalDivider))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                itemsIndexed(state.screen, key = { i, _ -> i }) { _, line ->
                    Text(
                        text = renderLine(line, pal.terminalText),
                        style = MonoTextStyle.copy(fontSize = 13.sp),
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
            LaunchedEffect(state.screen.size, state.screen.lastOrNull()?.text) {
                if (state.screen.isNotEmpty()) listState.scrollToItem(state.screen.size - 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.terminalDivider))

            // 功能键上排：手机没有的修饰键，独立一行，彼此隔开
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "Ctrl-C" to { onEvent(TerminalEvent.Input("\u0003")) },
                    "Ctrl-Z" to { onEvent(TerminalEvent.Input("\u001a")) },
                    "Ctrl-D" to { onEvent(TerminalEvent.Input("\u0004")) },
                    "Tab" to { onEvent(TerminalEvent.Key("TAB")) },
                    "Esc" to { onEvent(TerminalEvent.Key("ESCAPE")) },
                    "Enter" to { onEvent(TerminalEvent.Input("\n")) },
                ).forEach { (label, action) -> KeyButton(label, pal, Modifier.weight(1f), action) }
            }

            // 命令行输入
            TextField(
                value = input,
                onValueChange = { v ->
                    val tail = if (v.startsWith(lastValue)) v.removePrefix(lastValue) else v
                    if (tail.isNotEmpty()) onEvent(TerminalEvent.Input(tail))
                    lastValue = v
                    input = ""
                },
                modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp).onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val name = keyName(e.key.nativeKeyCode) ?: return@onKeyEvent false
                    onEvent(TerminalEvent.Key(name, e.isCtrlPressed, e.isAltPressed, e.isShiftPressed))
                    true
                },
                singleLine = true,
                textStyle = MonoTextStyle.copy(color = pal.terminalText, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEvent(TerminalEvent.Input("\n")) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = pal.terminalText,
                    focusedTextColor = pal.terminalText,
                    unfocusedTextColor = pal.terminalText,
                ),
                placeholder = { Text("输入命令…", style = MonoTextStyle, color = pal.terminalMuted.copy(alpha = 0.6f)) },
            )

            // 光标键下排：触屏可点的方向/编辑键，独立一行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "↑" to "UP", "↓" to "DOWN", "←" to "LEFT", "→" to "RIGHT",
                    "Home" to "HOME", "End" to "END", "⌫" to "BACKSPACE", "Del" to "DELETE",
                ).forEach { (label, key) ->
                    KeyButton(label, pal, Modifier.weight(1f)) { onEvent(TerminalEvent.Key(key)) }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, pal: lobos.ui.theme.LobosPalette, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MonoTextStyle.copy(fontSize = 12.sp, color = pal.terminalText),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(pal.terminalDivider.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
    )
}

private fun renderLine(line: TerminalLine, defaultColor: Color): AnnotatedString = buildAnnotatedString {
    line.chars.forEach { sc ->
        withStyle(SpanStyle(color = sc.color?.let { Color(it) } ?: defaultColor, fontWeight = if (sc.bold) FontWeight.Bold else FontWeight.Normal)) {
            append(sc.c.toString())
        }
    }
}
