package lobos.pieces

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
        Item("session", "进程树回收：子进程继承宿主进程组（通用 APK 无法 setpgid），故按 /proc ppid 链扫后代逐个回收", "done", "os/ProcessLedger（descendantsOf + killTree）"),
        Item("case-sensitive", "大小写敏感（Linux 语义）", "done", "Android 文件系统（ext4/f2fs）本身大小写敏感；本系统不做任何不敏感化处理"),
        Item("uid-model", "单用户全权限（无多用户语义）", "done", "模型决策：单租户"),
        Item("signal", "信号与退出码透传", "done", "对账本条目及其后代发 SIGTERM，超时未退再 SIGKILL；退出码入账（InstanceHost/ProcessLedger）"),
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
        lobos.os.StateFiles.writeJson(File(SystemDirs.libvar(ctx), "compat-semantics.json"), o)
    }
}
