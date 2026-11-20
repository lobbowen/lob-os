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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lobos.ui.theme.LobosTheme

private val TerminalBackground = Color(0xFF141821)
private val TerminalBorder = Color(0xFF2A2F3A)
private val TerminalDivider = Color(0xFF242A36)
private val TerminalMuted = Color(0xFF8A93A4)
private val TerminalPromptUser = Color(0xFF5EE08A)
private val TerminalPromptPath = Color(0xFF6AA9FF)
private val TerminalText = Color(0xFFE6EAF2)
private val TerminalQuickCommands = listOf("python3", "ls", "cat", "help")
private const val PromptPrefix = "lobos@os:~$ "

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onEvent: (TerminalEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (state) {
            is TerminalUiState.Ready -> TerminalReady(state, onEvent, modifier = Modifier)
            is TerminalUiState.Error -> TerminalError(state.msg, modifier = Modifier)
        }
    }
}

@Composable
private fun TerminalReady(
    state: TerminalUiState.Ready,
    onEvent: (TerminalEvent) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val terminalShape = RoundedCornerShape(20.dp)
    var input by remember(state.prompt) { mutableStateOf(state.prompt) }
    val submit: (String) -> Unit = { value ->
        val cmd = value.trim()
        if (cmd.isNotEmpty()) {
            onEvent(TerminalEvent.Submit(cmd))
        }
        input = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Text("终端", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Spacer(Modifier.height(5.dp))
        Text("在 LobOS 中运行命令", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(terminalShape)
                .background(TerminalBackground)
                .border(1.dp, TerminalBorder, terminalShape),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840)).forEach { color ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.size(7.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = AnnotatedString("bash · aarch64"),
                    color = TerminalMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TerminalDivider))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.lines, key = { index, _ -> index }) { _, line ->
                    Text(
                        text = terminalLine(line),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = TerminalText,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(TerminalDivider))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("›", color = TerminalMuted, fontSize = 17.sp, fontFamily = FontFamily.Monospace)
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = TerminalText,
                    ),
                    placeholder = {
                        Text(
                            "输入命令...",
                            color = Color(0xFF5D6676),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { submit(input) },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = TerminalText,
                        unfocusedTextColor = TerminalText,
                        cursorColor = TerminalPromptPath,
                        focusedPlaceholderColor = Color(0xFF5D6676),
                        unfocusedPlaceholderColor = Color(0xFF5D6676),
                    ),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalQuickCommands.forEach { command ->
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { input = command },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = command,
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = cs.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalError(message: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val terminalShape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Text("终端", style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
        Spacer(Modifier.height(5.dp))
        Text("在 LobOS 中运行命令", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(terminalShape)
                .background(TerminalBackground)
                .border(1.dp, TerminalBorder, terminalShape)
                .padding(20.dp),
        ) {
            Text(
                text = message,
                color = Color(0xFFFF8A8A),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
    }
}

private fun terminalLine(line: String): AnnotatedString {
    if (!line.startsWith(PromptPrefix)) {
        return AnnotatedString(line)
    }
    return buildAnnotatedString {
        withStyle(SpanStyle(color = TerminalPromptUser, fontWeight = FontWeight(600))) {
            append("lobos")
        }
        withStyle(SpanStyle(color = TerminalPromptPath)) {
            append("@os:~$ ")
        }
        withStyle(SpanStyle(color = TerminalText)) {
            append(line.removePrefix(PromptPrefix))
        }
    }
}

private val previewLines = listOf(
    "${PromptPrefix}python3 --version",
    "Python 3.14.8",
    "",
    "${PromptPrefix}ls /usr/bin",
    "bash busybox cmake git python3 rg",
    PromptPrefix.trimEnd(),
)

@Preview(showBackground = true)
@Composable
private fun TerminalReadyPreview() {
    LobosTheme(dynamicColor = false) {
        TerminalScreen(
            state = TerminalUiState.Ready(previewLines),
            onEvent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TerminalErrorPreview() {
    LobosTheme(dynamicColor = false) {
        TerminalScreen(
            state = TerminalUiState.Error("shell 会话启动失败: EACCES"),
            onEvent = {},
        )
    }
}
