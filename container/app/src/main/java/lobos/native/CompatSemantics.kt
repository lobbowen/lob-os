package lobos.native

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object CompatSemantics {

    data class Item(val id: String, val semantics: String, val status: String, val carrier: String)

    private val ITEMS = listOf(
        Item("tmp", "/tmp 可写且进程私有视图", "done", "d1/tmp-paths.c"),
        Item("symlink", "符号链接解析与 /usr/bin 查找", "done", "d1/link-interpose.c"),
        Item("exec-bit", "可执行位与解释器路径（shebang）", "done", "d1/exec-path.c"),
        Item("open-fallback", "打不开时的回落语义（不静默）", "done", "d1/open-fallback.c"),
        Item("flock", "文件锁语义", "done", "d2/flock.c"),
        Item("pty", "伪终端语义", "done", "d2/pty-probe.c"),
        Item("session", "会话/进程组：内核在 spawn 时 setpgid 建组，账本记归属，仅回收自建组", "done", "os/ProcessLedger（setpgid + ownsGroup）"),
        Item("case-sensitive", "大小写敏感（Linux 语义）", "done", "Android 文件系统（ext4/f2fs）本身大小写敏感；本系统不做任何不敏感化处理"),
        Item("uid-model", "单用户全权限（无多用户语义）", "done", "模型决策：单租户"),
        Item("signal", "信号与退出码透传", "done", "仅对账本标记 ownsGroup 的组发 SIGTERM；其余只杀单个 pid。退出码入账（InstanceHost/ProcessLedger）"),
    )

    fun write(ctx: Context) {
        val arr = JSONArray()
        for (i in ITEMS) {
            arr.put(
                JSONObject().apply {
                    put("id", i.id)
                    put("semantics", i.semantics)
                    put("status", i.status)
                    put("carrier", i.carrier)
                },
            )
        }
        val done = ITEMS.count { it.status == "done" }
        val o = JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("total", ITEMS.size)
            put("done", done)
            put("pending", ITEMS.count { it.status != "done" })
            put("items", arr)
        }
        lobos.os.StateFiles.writeJson(File(File(ctx.filesDir, "os"), "compat-semantics.json"), o)
    }
}
