package lobos.pieces

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 兼容语义 —— 与 Linux 的逐项对照，**如实标注**。
 *
 * 此前十项全标 "done"。核实之后发现其中四项名不副实：
 * 「我们决定不做」和「我们做到了 Linux 的语义」是两件事，不能都写成 done。
 *
 * 四档：
 *   aligned   机制与 Linux 相同
 *   partial   机制对，但有已知缺口
 *   differ    用别的机制达成相近效果（不是 Linux 那个）
 *   absent    Linux 有，我们没有 —— 这一档是短板，不是成就
 */
object CompatSemantics {

    data class Item(
        val id: String,
        val semantics: String,
        val status: String,
        val carrier: String,
    ) {
        val aligned: Boolean get() = status == "aligned"
        val absent: Boolean get() = status == "absent"
    }

    private val ITEMS = listOf(
        Item(
            "tmp", "/tmp 可写且进程私有视图",
            "differ",
            "d1/tmp-paths.c（LD_PRELOAD 路径重定向）",
        ),
        Item(
            "symlink", "符号链接解析与 /usr/bin 查找",
            "aligned",
            "d1/link-interpose.c + ExecBits",
        ),
        Item(
            "exec-bit", "可执行位与解释器路径（shebang）",
            "partial",
            "ExecBits 读 e_type + PT_INTERP。"
                + "缺 binfmt_misc(5) 那层：Linux 内核能按任意魔数选解释器，我们只认 ELF 与 #!",
        ),
        Item(
            "open-fallback", "打不开时的回落语义（不静默）",
            "aligned",
            "d1/open-fallback.c",
        ),
        Item(
            "flock", "文件锁语义",
            "aligned",
            "d2/flock.c（flock(2)；fcntl(F_SETLK) 那套 POSIX 锁未做）",
        ),
        Item(
            "pty", "伪终端语义",
            "aligned",
            "d2/pty-probe.c + d3/pty-session.c",
        ),
        Item(
            "session",
            "进程树回收",
            "partial",
            "通用 APK 无 setpgid/setcgroup 权限，按 /proc ppid 链扫后代逐个 SIGTERM→SIGKILL。"
                + "两个已知缺口：扫描期间 fork 出的新后代会漏；"
                + "没有内核级进程组概念，全靠应用层追（systemd 是 cgroup.kill 一次收干净）",
        ),
        Item(
            "case-sensitive", "大小写敏感（Linux 语义）",
            "aligned",
            "ext4/f2fs 本身大小写敏感；本系统不做任何不敏感化处理",
        ),
        Item(
            // ★ 这是最大的一块短板，此前错标成 done
            "uid-model", "uid 身份与权限边界",
            "absent",
            "【无实现】全仓 uid / gid / setuid / setgid / chown / setuid-bit 零出现。"
                + "所有程序以同一个 uid（APK 的 uid）跑，彼此权限完全一样。"
                + "对照 Linux 这一整串：user(5) 账号 → chown(1) 改属主 → setuid(2) 改身份 → "
                + "setuid 位以属主身份运行 → capability(7) 把特权拆细 → "
                + "user_namespaces(7) 非特权起自己的 uid 空间。"
                + "我们一条都没有 —— 这是「前后端分体」这个形态的直接后果："
                + "控制面在 APK 进程里，数据面的程序是它拉起的普通子进程，同一个 uid。"
                + "要补它，得先决定「程序之间要不要互相隔离」这个产品问题",
        ),
        Item(
            "signal", "信号与退出码透传",
            "partial",
            "对账本条目及其后代发 SIGTERM，超时 SIGKILL，退出码入账。"
                + "缺口：只发这两个信号，程序之间互相发的信号全丢 —— "
                + "signal(7) 的完整语义没有",
        ),
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
        val o = JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("total", ITEMS.size)
            put("aligned", ITEMS.count { it.aligned })
            put("partial", ITEMS.count { it.status == "partial" })
            put("differ", ITEMS.count { it.status == "differ" })
            put("absent", ITEMS.count { it.absent })
            put("items", arr)
        }
        lobos.os.StateFiles.writeJson(File(SystemDirs.libvar(ctx), "compat-semantics.json"), o)
    }
}