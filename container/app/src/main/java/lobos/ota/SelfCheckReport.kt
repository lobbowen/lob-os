package lobos.ota

object SelfCheckReport {

    data class Item(
        val id: String,
        val ok: Boolean?,
        val title: String,
        val detail: String = "",
    )

    fun passed(items: List<Item>): Int = items.count { it.ok == true }
    fun failed(items: List<Item>): Int = items.count { it.ok == false }
    fun unknown(items: List<Item>): Int = items.count { it.ok == null }

    fun partialItem(parts: List<Pair<String, Long>>, staging: List<String> = emptyList()): Item {
        val hollow = parts.filter { it.second <= 0L }.map { it.first }
        val detail = buildString {
            append(
                if (parts.isEmpty()) "无半包（没有中断过的下载）"
                else "半包会被续传（非错误）：" + parts.joinToString(", ") { "${it.first}=${it.second}B" }
            )
            if (staging.isNotEmpty()) append("   安装正在进行（暂存）：" + staging.joinToString(", "))
            if (hollow.isNotEmpty()) append("   无可续传内容的空半包：" + hollow.joinToString(", "))
        }
        return Item("partial", hollow.isEmpty(), "下载半包", detail)
    }

    fun overallOk(items: List<Item>): Boolean? = when {
        items.isEmpty() -> null
        failed(items) > 0 -> false
        unknown(items) > 0 -> null
        else -> true
    }

    fun verdict(items: List<Item>): String {
        if (items.isEmpty()) return "自检：无检查项"
        val total = items.size
        val f = failed(items)
        val u = unknown(items)
        return when {
            f == 0 && u == 0 -> "✅ 自检通过（" + total + "/" + total + "）"
            f == 0 -> "⚠ 自检 " + passed(items) + "/" + total + " 通过，另有 " + u + " 项未知（未测到 ≠ 没问题）"
            else -> "❌ 自检 " + passed(items) + "/" + total + " 通过，" + f + " 项失败" + if (u > 0) "，另有 " + u + " 项未知" else ""
        }
    }

    fun format(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append(verdict(items)).append("\n")
        for (it in items) {
            val mark = when (it.ok) {
                true -> "✅"
                false -> "❌"
                null -> "❔"
            }
            sb.append(mark).append(" ").append(it.title)
            if (it.detail.isNotBlank()) sb.append("\n     ").append(it.detail)
            sb.append("\n")
        }
        return sb.toString()
    }
}
