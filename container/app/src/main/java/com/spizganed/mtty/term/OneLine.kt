package com.spizganed.mtty.term

/**
 * Joins copied lines into one line: for a command that a program (Claude Code, a pager) wrapped with real line breaks.
 * Each line loses its indent and its trailing spaces. A shell continuation ("\" at the end) goes away.
 * A word longer than the width gets cut: its line is only that word and fills the width of the block. Such a line joins the
 * next with no space (a long URL or path stays whole). Other lines join with a space.
 * Empty lines go away. A line that starts with "!" (a shell command of Claude Code) or "$ " (a prompt) starts a new command:
 * it keeps its line break, so three "!" commands stay three lines.
 */
fun oneLine(text: String): String {
    val lines = text.split('\n').map { it.trimEnd() }
    val width = lines.maxOfOrNull { it.length } ?: 0
    val sb = StringBuilder()
    var cut = false
    for (raw in lines) {
        var line = raw.trim()
        if (line.isEmpty()) continue
        val cont = line.endsWith("\\")
        if (cont) line = line.dropLast(1).trimEnd()
        if (sb.isNotEmpty() && (line.startsWith("!") || line.startsWith("$ "))) sb.append('\n')
        else if (sb.isNotEmpty() && !cut) sb.append(' ')
        sb.append(line)
        cut = !cont && raw.length == width && ' ' !in line
    }
    return sb.toString()
}
