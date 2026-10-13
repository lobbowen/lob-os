package lobos.kernel.sysdev

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.ipc.PtySession
import lobos.kernel.layout.SystemDirs
import lobos.kernel.proc.ProcessLedger

object SysDev {

    fun root(ctx: Context): File = File(ctx.filesDir, "dev")

    fun render(ctx: Context): File? = runCatching {
        val r = root(ctx)
        wipe(r)
        r.mkdirs()
        ttyDir(ctx, r)
        execNode(r)
        indexJson(ctx, r)
        r
    }.getOrNull()

    private fun wipe(d: File) {
        if (!d.isDirectory) return
        d.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun ttyDir(ctx: Context, r: File) {
        val pts = File(r, "tty/pts").apply { mkdirs() }
        val rows = JSONArray()
        for (s in PtySession.activeSessions(ctx)) {
            val n = String.format("%03d", s.sid)
            val f = File(pts, n)
            f.writeText(
                buildString {
                    append("sid=").append(s.sid).append('\n')
                    append("slave=").append(s.slavePath).append('\n')
                    append("rows=").append(s.initialRows).append('\n')
                    append("cols=").append(s.initialCols).append('\n').append('\n')
                }
            )
            File(pts, "$n/slave").let { d ->
                d.mkdirs()
                runCatching {
                    java.nio.file.Files.createSymbolicLink(
                        File(d, "path").toPath(),
                        java.nio.file.Paths.get(s.slavePath)
                    )
                }
            }
            rows.put(JSONObject().put("sid", s.sid).put("slave", s.slavePath))
        }
        File(r, "tty/index.json").writeText(rows.toString())
    }

    private fun execNode(r: File) {
        val d = File(r, "exec").apply { mkdirs() }
        File(d, "shell.exec").writeText(
            "lobos.sys.shell.exec\n" +
                "shell.exec 是发起式调用：提交并拿结果，不属于可读事实。\n" +
                "它经 CapabilityBroker 的 unix socket + JSON-RPC 提供，\n" +
                "所以这里只登记名字，不假装它是可读的。\n"
        )
    }

    private fun indexJson(ctx: Context, r: File) {
        val o = JSONObject()
        val tty = JSONArray()
        for (s in PtySession.activeSessions(ctx)) {
            tty.put(JSONObject().put("sid", s.sid).put("slave", s.slavePath))
        }
        o.put("tty", tty)
        o.put("programs", JSONArray().apply {
            for (e in ProcessLedger.list(ctx)) {
                put(JSONObject().put("programId", e.programId).put("pid", e.pid))
            }
        })
        o.put("lib", SystemDirs.lib(ctx).absolutePath)
        o.put("bin", SystemDirs.bin(ctx).absolutePath)
        File(r, "index.json").writeText(o.toString(2))
    }
}
