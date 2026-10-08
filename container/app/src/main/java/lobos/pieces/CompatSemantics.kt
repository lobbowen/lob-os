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
 *   decided   我们决定不做 —— **形态决定的取舍，不是待办**
 *   absent    Linux 有，我们没有，且还没决定要不要做 —— **真缺口**
 */
object CompatSemantics {

    data class Item(
        val id: String,
        val semantics: String,
        val status: String,
        val carrier: String,
    ) {
        val aligned: Boolean get() = status == "aligned"
        /** Linux 有、我们没有，且还没决定要不要做 —— 真缺口 */
        val absent: Boolean get() = status == "absent"
        val decided: Boolean get() = status == "decided"
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
            "decided",
            "ExecBits 读 ELF 的 e_type 与 PT_INTERP，两种形态都能判。"
                + "缺 binfmt_misc(5) 那层（内核按任意魔数选解释器，如 .jar→java .py→python3）"
                + "——形态决定不做：我们按**名字**派发（usr/bin/<名字> 软链，ldconfig 那一套），"
                + "不按扩展名。程序都由控制面板或 OTA 给出，不存在「用户放个文件点一下就跑」的场景",
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
            "session", "进程树回收",
            "partial",
            "【形态决定】通用 APK 无 setpgid / setcgroup 权限，拿不到内核级进程组"
                + "（systemd 是 cgroup.kill 一次收干净）—— 只能按 /proc ppid 链扫后代，"
                + "逐个 SIGTERM，超时 SIGKILL。"
                + "【真短板】扫描期间 fork 出的新后代会漏 —— 它们成了不在账本里的孤儿进程。"
                + "补法：扫到收敛为止（重复扫到没有新后代），或按 /proc/<pid>/stat 的 starttime "
                + "判进程是否还是同一个（防 pid 复用）",
        ),
        Item(
            "case-sensitive", "大小写敏感（Linux 语义）",
            "aligned",
            "ext4/f2fs 本身大小写敏感；本系统不做任何不敏感化处理",
        ),
        Item(
            // 单用户是**形态决定**，不是待办
            "uid-model", "uid 身份与权限边界",
            "decided",
            "形态决定：不做多用户。理由——我们是在安卓上模拟出来的一套系统，"
                + "不是安卓本身。"
                + "① 文件系统在应用私有目录 filesDir 下自成一套（etc/usr/var/run/opt）。"
                + "★ 但要说清：这只在**逻辑上**自成一套 —— 物理上它们仍在安卓的 / 之下，"
                + "程序里的绝对路径会落到安卓的真实位置（见 isolation 那一项）。"
                + "我们代码里 /data/data 与 su 零出现，那只说明**我们自己没写这些路径**，"
                + "不等于系统碰不到。"
                + "② 程序都是我们拉起的普通子进程，与控制面同 uid（APK 的 uid），"
                + "彼此权限完全一样 —— 系统内不存在「这个程序能、那个不能」的权限差。"
                + "③ 与 Linux 的差别在根本处：Linux 的前后端一体，权限是内核里的事；"
                + "我们前后端分体，程序不是安卓的应用，没有 uid 意义上的身份。"
                + "④ 需要安卓权限时走 capability 包里那三个组件"
                + "（无障碍 · MediaProjection · ADB 通道），由它们代取。"
                + "对照 Linux 缺的：user(5) 账号 · chown(1) · setuid(2) · setuid 位 · "
                + "capability(7) 特权拆细 · user_namespaces(7)。"
                + "这是形态的差别，不是没做完 —— 所以状态是 decided 不是 absent",
        ),
        Item(
            // ★ 这一项此前错标成 aligned —— 核实后发现不成立
            "isolation", "与安卓系统的文件系统边界",
            "absent",
            "【现状】我们的系统根在物理上是 filesDir（/data/user/0/<包>/files），"
                + "usr/opt/var/etc 都在安卓真实的 / 之下 —— 是**嵌套**，不是隔离。"
                + "我们的 / 只在逻辑上存在：没有任何一层把 \"/data/data\" 变成查无此目录。"
                + "核实 chroot / unshare / pivot_root / CLONE_NEWNS 全仓零出现。"
                + "现有四个 LD_PRELOAD hook（exec-path 查解释器 · open-fallback "
                + "EACCES 回退 HOME · link-interpose 拦 link/linkat · tmp-paths 改 /tmp）"
                + "都不是路径重映射。"
                + "【后果】程序里任何绝对路径都落到安卓的真实位置 —— "
                + "它 open(\"/data/data/…\") 读到的是安卓的目录。"
                + "【Linux 的机制】chroot(2)/pivot_root(2)/unshare(CLONE_NEWNS) —— "
                + "给进程一个自己的 /，namespace 外看不见。我们一个都没有。"
                + "【为什么难】通用 APK 无 CAP_SYS_CHROOT，SELinux 下 mount namespace "
                + "基本不可得；ADB 通道能拿但那是单独启用的组件，不是常态。"
                + "所以这一项是**真缺口**，不是形态决定的取舍 —— "
                + "单用户那套逻辑成立（程序之间不互相隔离是形态决定），"
                + "但与安卓之间的边界没建立起来",
        ),
        Item(
            "signal", "信号与退出码透传",
            "partial",
            "停程序：对账本条目及其后代发 SIGTERM，超时 SIGKILL；退出码入账。"
                + "PtySession 有 signalName() 把信号号译成名字做诊断，"
                + "但那是**读**退出原因，不是发信号。"
                + "【真短板】没有「按名字向某个 pid 发任意信号」的通道 —— "
                + "同 uid 下 kill(pid, sig) 本来完全可行，做而未做。"
                + "对照 signal(7)：我们只覆盖 SIGTERM/SIGKILL 两个，其余全无",
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
            put("decided", ITEMS.count { it.status == "decided" })
        put("absent", ITEMS.count { it.absent })
            put("items", arr)
        }
        lobos.os.StateFiles.writeJson(File(SystemDirs.libvar(ctx), "compat-semantics.json"), o)
    }
}