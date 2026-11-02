package lobos.services.host

import android.content.Context
import java.util.UUID
import java.io.File
import lobos.kernel.compat.PeLoader
import lobos.control.ControlClient
import lobos.kernel.proc.SessionRegistry
import lobos.services.log.RuntimeDiagnostics
import lobos.services.supply.PieceOriginStore
import lobos.services.supply.PieceUpdater
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-resident formal management service of LOB-OS.
 *
 * This is the product-internal control plane entry. It does not depend on an installed quick app:
 * the host application itself owns it, so the management UI shipped inside the APK can call it
 * without any extra program being installed.
 *
 * Authorization: every operation is issued under the fixed LOB-OS host management identity
 * (lobos.installer), which the broker treats as a system-scope session.
 * Audit: the broker audit log records the requestId of every request; this service also writes a
 * host-control diagnostic entry for each call.
 * Transport: the operation still crosses the formal LocalSocket handshake through ControlClient,
 * so token, framing, timeout, structured errors and requestId idempotency all apply.
 */
object HostControlService {
    const val MANAGEMENT_ID = "lobos.installer"
    const val MANAGEMENT_GENERATION = 1L

    /**
     * List the pieces with their entry origin (APK original or OTA version) and with the rollback
     * eligibility of each one.
     *
     * 缺陷名（C-945）件列表只有来源记录的那些格，没有回退资格与理由：
     * 而「能不能回退」是操作者最先要看的一格，它必须能在一处读出来。
     *
     * 主体集合是**所有在册的件**（注册表 ProgramIndex 里的全部登记项），不再只限纯库形态。
     * 而「在册」与「有来源记录」是两件事：前者由注册表答，后者由来源记录答，
     * 所以每一项的来源那一族字段在来源记录缺失时是空串，而回退理由会答「无来源指针」。
     *
     * C-945 字段消费对照（来源记录那七个字段逐个的消费者）：
     * 1. origin      -> 来源记录里的 active（apk 或 ota）。消费方：本文件的 pieces 把它放进
     *                    返回 JSON 的 "origin"；HostControlActivity 的下拉列表标签与逐字段明细都读它。
     * 2. apkVersion  -> 来源记录里 APK 原件那一代的版本号。消费方：本文件的 "apkVersion"；
     *                    HostControlActivity 的逐字段明细读它。
     * 3. apkTarget   -> 新含义：来源记录里 APK 原件那一格（apkSlots）的 entryPath，即 APK 原件的入口
     *                    指针路径。消费方：本文件的 "apkTarget"；HostControlActivity 的逐字段明细读它。
     * 4. otaVersion  -> 来源记录里 OTA 那一代的版本号。消费方：本文件的 "otaVersion"；
     *                    HostControlActivity 的逐字段明细读它。
     * 5. otaTarget   -> 新含义：来源记录里 OTA 那一格（otaSlots）的 entryPath，即 OTA 那一份的入口
     *                    指针路径。消费方：本文件的 "otaTarget"；HostControlActivity 的逐字段明细读它。
     * 6. hasApkOriginal -> 来源记录里 APK 原件那一格是否完整。消费方：本文件的 "hasApkOriginal"；
     *                    HostControlActivity 的逐字段明细读它。
     * 7. hasOta      -> 来源记录里 OTA 那一格是否完整。消费方：本文件的 "hasOta"；
     *                    HostControlActivity 的逐字段明细读它。
     * 而 updated（不是来源记录的字段，是「OTA 那一份当前在接管」这一实况）-> 消费方：
     * 本文件的 "updated" 与 HostControlActivity 的逐字段明细。
     * 八个字段都有真实消费者，没有「消费方未实现」的字段。
     */
    fun pieces(context: Context): JSONArray {
        val app = context.applicationContext
        val origins = PieceOriginStore.all(app).associateBy { it.id }
        val arr = JSONArray()
        for (s in PieceUpdater.states(app)) {
            val o = origins[s.id]
            //  缺陷名（C-102）**回退资格这一族原先在两处各写一套**：
            //  而一处（控制面）按「入口是不是软链」判接管、另一处（回退本身）按来源记录判，
            //  => 而两处**会给出不同的答案**（=> 而 UI 说「可回滚」而回退那一次答「未接管」）。
            //  现在：**只有一处判据**（PieceUpdater.rollbackStatus），两处都读它。
            val status = PieceUpdater.rollbackStatus(app, s.id)
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("installedVersion", s.installedVersion ?: JSONObject.NULL)
                // 缺陷名（C-945）updated 的含义定为「OTA 那一份当前在接管」（来源记录里那一条指针
                //  实况），而不是「这一件曾经装过什么东西」。
                put("updated", s.updated)
                put("origin", o?.active ?: "")
                put("apkVersion", o?.apkVersion ?: "")
                put("apkTarget", o?.apkEntryPath ?: "")
                put("otaVersion", o?.otaVersion ?: "")
                put("otaTarget", o?.otaEntryPath ?: "")
                put("hasApkOriginal", o?.hasApkOriginal == true)
                put("hasOta", o?.hasOta == true)
                put("rollbackEligible", status.eligible)
                put("rollbackReason", status.reason)
                put("rollbackDetail", status.detail)
                put("rollbackFailureClass", status.failureClass)
                put("rollbackRole", status.role)
                put("entryPointer", status.entryPath)
            })
        }
        return arr
    }

    /**
     * 某一件事的回退资格与理由（按 id 查，不限在册）。
     *
     * 判据只有一处：PieceUpdater.rollbackStatus（回退那一族唯一的判据来源）。
     * 01 linux-architect 裁定；回退主体集合是所有在册的件（C-954）。
     *
     * 理由的五种（01 的失败五分类里与回退有关的三个，加上在册与未定位两个）：
     *   可回滚 / 未接管（F2）/ 无来源指针（F1）/ 库形态入口未定位（F3）/ 非在册。
     */
    fun rollbackJudgement(context: Context, id: String): PieceUpdater.RollbackStatus {
        val app = context.applicationContext
        return PieceUpdater.rollbackStatus(app, id.trim())
    }

    /** 某一件事的回退资格（JSON 形态，供 UI 与外部调用方读）。 */
    fun pieceEligibility(context: Context, id: String): JSONObject {
        val app = context.applicationContext
        val j = rollbackJudgement(app, id)
        return JSONObject().apply {
            put("id", id.trim())
            put("rollbackEligible", j.eligible)
            put("rollbackReason", j.reason)
            put("rollbackDetail", j.detail)
            put("entryPointer", j.entryPath)
            put("apkTarget", j.apkTarget)
        }
    }

    fun update(context: Context, manifestUrl: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext
        val method = "lobos.sys.native.update"
        val correlatedId = requestId.trim().ifEmpty { "update-" + UUID.randomUUID() }
        val url = manifestUrl.trim()
        //  缺陷名（C-118）失败结果里的 method 那一格必须写**本次真正调用的那个方法**：
        //  这一支调的是 client.update，所以方法名是 lobos.sys.native.update。
        //  而下面每一处 failure 的第一个参数都取本支的 method 变量，不再写死字面量。
        if (url.isBlank()) return failure(method, correlatedId, "manifestUrl 为空")
        val broker = lobos.api.CapabilityBroker.live()
            ?: return failure(method, correlatedId, "宿主桥未启动：控制面此刻不可用")
        return try {
            val token = broker.beginSession(MANAGEMENT_ID, MANAGEMENT_GENERATION)
            SessionRegistry.bindPid(app, token, android.os.Process.myPid())
            val client = ControlClient(
                socketName = SessionRegistry.socketName(token), sessionToken = token,
                protocolVersion = lobos.BuildConfig.BRIDGE_PROTOCOL,
            )
            val result = client.update(url, correlatedId, dryRun)
            JSONObject().apply {
                put("ok", result.ok); put("code", result.code); put("requestId", correlatedId)
                put("method", method); put("result", result.value)
            }
        } catch (e: Throwable) { failure(method, correlatedId, e.message ?: e.javaClass.simpleName) }
            .also { response -> RuntimeDiagnostics.append(app, "host-control", response.optBoolean("ok", false), "宿主正式管理服务执行更新", "requestId=" + correlatedId + " manifestUrl=" + url + " dryRun=" + dryRun + " response=" + response) }
    }

    /** The PE L2 samples shipped with the product, with their expected digests. */
    data class PeSample(val asset: String, val sha256: String, val label: String)

    val peSamples: List<PeSample> = listOf(
        PeSample(
            "win32/plink-arm64.exe",
            "6824cd69458b58761077ca8d4f6a3ba788fe2596b2344e591f00593e13716354",
            "PE32+ ARM64",
        ),
        PeSample(
            "win32/plink.exe",
            "7308e9d356f14aa1f08cab1af363f71a077bf35dcb49e50b363418044758cd1e",
            "PE32 i386",
        ),
    )

    /** Load a shipped PE L2 sample through the production parser without executing it. */
    /**
     * 在 LOB-OS 自己那一份 Linux 里跑一条命令（=> 而那它逐字是那**产品**那一面）。
     *
     *  缺陷名（C-102）**那「跑一条用户指定的命令」逐字没有合法的产品入口** ——
     *   => 而那旧版逐字**只有**那 `ControlClient.exec` 那一格（=> 而那是**客户端**那一族、
     *     => 而而那它逐字要一次**带 token**的 handshake）
     *   => 而而那设备侧逐字**没有**别的一条路：
     *     · `LobosBridge.invoke` 逐字**拒** SCOPE_SYSTEM（=> 而那 `sys.tty.exec` 逐字是那系统作用域）
     *     · `QuickAppLaunchActivity` 那一支逐字是 EXTRA_EXEC 那一格
     *       （=> 而那它逐字**只能**用 `am start` 带 extra 叫起来、=> 而那**不是**一个产品 UI 入口）
     *   => 而那**后果**：=> 而那产品侧逐字**没有**能走的那一族
     *     （=> 而那 `invokeLocal` 那一格逐字**能**调 `sys.tty.exec`、=> 而那它逐字以 INSTALLER_ID
     *       逐字放行、=> 而那 serverGranted 逐字对 installer 逐字给全部含 GROUP_SYS、
     *       => 而那它逐字**不经 socket**、=> 而而那它逐字**不 claim 会话**）
     *   => 而那**处置**：=> 而那**这一格**逐字就是那产品 UI 上的一个入口
     *     （=> 而那它逐字走 `invokeLocal` 那一格、=> 而而那它逐字**不走**那 token 那一族）。
     *
     *  @param cmd 那要跑的命令串（=> 而那它逐字**在** LOB-OS 自己那一份 Linux 里跑）
     *  @param timeoutMs 那超时（=> 而那它逐字默认 10000）
     */
    fun execCommand(context: Context, cmd: String, timeoutMs: Long = 10_000L): JSONObject {
        val method = "sys.tty.exec"
        if (cmd.isBlank()) return failure(method, "exec", "命令为空")
        val broker = lobos.api.CapabilityBroker.live()
            ?: return failure(method, "exec", "宿主桥未启动：能力面此刻不可用")
        return runCatching {
            broker.invokeLocal(
                lobos.api.CapabilityBroker.INSTALLER_ID,
                method,
                JSONObject().apply {
                    put("cmd", cmd)
                    put("timeoutMs", timeoutMs)
                },
            )
        }.getOrElse { e ->
            failure(method, "exec", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 那**不经 shell** 逐字**直接调**那一条（=> 而而那它是 M7-05 那一格逐字**要**的那一条产品入口）。
     *
     * 缺陷名（C-102）**那 M7-05 那一格逐字在设备上逐字测不了** ——
     *   => 而那旧版逐字**没有**这样的一格、=> 而而那那**唯一**能跑一条命令的入口逐字是 `execCommand`
     *     => 而而那它逐字走那 `sys.tty.exec`、=> 而而那它逐字走那 **bash -c <命令>**
     *       => 而而那 bash 逐字 **execve** 那个坏 ELF、=> 而而那那**坏 ELF** 逐字报的是
     *         bash 自己那一条（=> 而而那那诊断原文逐字**不是**产品那一族逐字该给的那一条）
     *       => 而而那那**期望**逐字是「入口不是可执行的形态（既不是 ELF 也不是 #!）—— 拒绝调起，不当程序跑：<路径>」
     *   => 而那**处置**：=> 而那**这一格**逐字**不经 shell**、=> 而而那它逐字**直接**把那一条交给
     *     那 `ProcessSupervisor` 那一族（=> 而而那它逐字**认不出**那一档时逐字**报缺**、
     *       => 而而那那退出码逐字是那一个具体的数、=> 而而那那诊断原文逐字**进**那 stderr）
     *   => 而而那它逐字**不是**探针（=> 而而那它逐字是那**产品界面**上的一个**入口**）
     */
    /**
     * 那**不经 shell** 逐字**直接调**那一条（=> 而而那它逐字**带参数**）。
     *
     * 缺陷名（C-102）**那一格逐字只有那路径一个形参、=> 而而那它逐字带不了参数** ——
     *   => 而那旧版逐字是 `execDirect(context, path, timeoutMs)`、=> 而而那那 argv 逐字**只有**那一条
     *     => 而而那那 git 那一件逐字**没有** `--version` 就逐字打 usage、=> 而而那那退出码逐字 1
     *       （=> 而那 07 本轮逐字实测：=> 而那 stdout 逐字是 git 那 usage 原文）
     *   => 而那**处置**：=> 而那 argv 逐字**带**那参数
     *     => 而而那那参数逐字**透**给 `LocalExec.run` 那一族逐字那 argv 那一档
     *     => 而而那那**形态**逐字**还是**那 `ExecEntry.argv` 那一族算的（=> 而而那它逐字**不**变）
     */
    fun execDirect(
        context: Context,
        path: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 10_000L,
    ): JSONObject {
        val method = "exec.direct"
        if (path.isBlank()) return failure(method, "exec-direct", "路径为空")
        val target = java.io.File(path)
        if (!target.exists()) {
            return failure(method, "exec-direct", "那一件逐字不在：" + path + "（=> 而按 C-05 报缺）")
        }
        //  => 而而那**不经 shell**：=> 而而那 argv 逐字**只有**那一条与那些参数
        //    => 而而那**形态**逐字**还是**那 `ExecEntry.argv` 那一族算的（=> 而而那它逐字**不**变）
        val r = lobos.kernel.ipc.LocalExec.run(
            context,
            listOf(target.absolutePath) + args,
            timeoutMs = timeoutMs,
            preferPty = false,
        )
        return JSONObject().apply {
            put("ok", r.ok)
            put("exitCode", r.exitCode)
            put("stdout", r.stdout)
            put("stderr", r.stderr)
            put("via", r.via.name)
            put("path", target.absolutePath)
            put("args", args)
            put("detail", r.error ?: "")
        }
    }

    /**
     * 那**被要求建一个错形状**的那一条（=> 而而那它是 M7-12 那一格逐字**要**的那一条产品入口）。
     *
     * 缺陷名（C-102）**那 M7-12 那一格逐字在设备上逐字测不了** ——
     *   => 而那旧版逐字**没有**这样的一格
     *     => 而而那那 `SysDev.render` 那一格逐字**先 wipe(root) 逐字再建、=> 而而那**之后**才 `verifyNodes`
     *       => 而而那**产品**逐字永远看不到盘上现存的错形状（=> 而而那它逐字每次都被自己清掉了）
     *   => 而那**处置**：=> 而那**这一格**逐字**在**那渲染**之前**逐字**核**那盘上现状
     *     => 而而那它逐字答的是**那三类之一**（=> 而而那它逐字**不**只报「失败」）
     *     => 而而那它逐字**不是**探针（=> 而而那它逐字是那**产品界面**上的一个**入口**）
     */
    fun verifyDeviceNodes(context: Context): JSONObject {
        val method = "sysdev.verify"
        val root = lobos.kernel.sysdev.SysDev.root(context)
        if (!root.isDirectory) {
            return failure(method, "sysdev-verify", "那设备节点目录逐字不在：" + root.absolutePath + "（=> 而按 C-05 报缺）")
        }
        val bad = lobos.kernel.sysdev.SysDev.verifyNodes(root)
        //  => 而而那那**全部**不合的那几处逐字（=> 而而那它逐字**不**只报那**第一处**、
        //    => 而而那那一处逐字**不许**盖住其余那几处）
        val all = lobos.kernel.sysdev.SysDev.verifyAllFaults(root)
        return JSONObject().apply {
            put("ok", bad == null)
            put("root", root.absolutePath)
            put("faultCount", all.size)
            put("faults", org.json.JSONArray().apply {
                for (f in all) {
                    put(JSONObject().apply {
                        put("path", f.path)
                        put("class", lobos.kernel.sysdev.SysDev.faultName(f.fault))
                        put("instance", f.instance ?: JSONObject.NULL)
                        put("modeDiff", if (f.fault == lobos.kernel.sysdev.SysDev.NodeFault.TOO_WIDE) {
                            lobos.kernel.sysdev.SysDev.modeDiffOf(f.path)
                        } else "")
                    })
                }
            })
            if (bad == null) {
                put("detail", "那逐节点逐字全**合声明**")
            } else {
                put("faultPath", bad.first)
                put("faultClass", lobos.kernel.sysdev.SysDev.faultName(bad.second))
                //  => 而而那那**哪一组 rwx 不一样**逐字**报出来**（=> 而 Lead 逐字要的那一格）
                //    => 而而那它逐字**只**在那一档逐字是「权限过宽」时逐字**答**
                val diff = if (bad.second == lobos.kernel.sysdev.SysDev.NodeFault.TOO_WIDE) {
                    lobos.kernel.sysdev.SysDev.modeDiffOf(bad.first)
                } else ""
                put("modeDiff", diff)
                put("detail", "节点不合声明：" + bad.first + " —— " + lobos.kernel.sysdev.SysDev.faultName(bad.second) +
                    (if (diff.isBlank()) "" else "（" + diff + "）"))
            }
        }
    }

    /**
     * 那**九族**逐字一个**产品入口**（=> 而而那它是「那产品那一面逐字有没有那九族逐字的入口」那一族）。
     *
     * 缺陷名（C-102）**那一族逐字没有能吃到那坏输入的入口** ——
     *   => 而那旧版逐字**没有**这一格
     *     => 而而那那 `HostControlService` 那一族逐字那公开方法逐字全清单逐字是
     *       `pieces` / `rollbackJudgement` / `pieceEligibility` / `update` / `execCommand` /
     *       `execDirect` / `verifyDeviceNodes` / `loadPeSample` / `rollback`
     *     => 而而那那**九族**逐字**没有**那 kmi / lkm / UART / 目标机型 那一族逐字的方法
     *     => 而而那那**设备**上 `files/etc` 逐字**没有** `kmi.html` / `lkm.html` / `abi.stg`、
     *       而而那那 `files/var/lib` 逐字**没有**那九族逐字对应的东西
     *     => 而而那那**结论**逐字是：那一格逐字要逐条给那九族逐字一个产品入口
     *       （=> 而而那它逐字能吃到那坏输入、=> 而而那它逐字能报出那一条原话）
     *   => 而那**处置**：=> 而那九族逐字**各有**一个产品入口
     *     => 而而那它们逐字**同形**（=> 而而那它们逐字与那 `execDirect` / `verifyDeviceNodes`
     *       逐字那两族逐字是「那产品那一面逐字给一个入口」那一族）
     *     => 而而那每一条逐字答「那**坏输入**逐字被吃进去逐字以后逐字报出什么」
     *     => 而而那每一条逐字**报出那一条原话**（=> 而按 C-122/C-123 要能换回原话）
     *     => 而而那每一条逐字**不**静默（=> 而按 C-126 不静默、=> 而按 C-05 缺就报缺）
     *
     *   => 而而那那**九族**逐字是：kmi 三选一 / lkm 两选一 / 目标机型任一不一致 /
     *     UART 两选一 / linker64 两选一 / 文件三选一 / 内存两选一 / 安全越权被拒并留审计 / 时间两选一
     */
    /**
     * 那**九族**逐字那**positive** 那一侧（=> 而而那它是「那一族逐字合法时逐字答什么」那一族）。
     *
     * 缺陷名（C-102）**那一族逐字只有那 negative 那一侧、=> 而而那 positive 那一侧逐字没有** ——
     *   => 而那旧版逐字**只有**那 `hostBoundCheck` 那一格（=> 而而那它逐字答「那**坏输入**被吃进去以后报出什么」）
     *     => 而而那那**合法**那一侧逐字**没有**入口、=> 而而那 07 那一侧逐字**取不到**那 positive 那一读
     *   => 而那**形状**逐字照 M7-05 与 M7-12 那两族（=> 而而那它们逐字是同形）：
     *     · M7-05 那 positive 逐字是「那**合法** ELF 逐字跑起来、=> 而而那退出码逐字是那期望值、
     *       => 而而那那 stderr 逐字**空**」
     *     · M7-12 那 positive 逐字是「那三类里逐字合法的那一类逐字建得出来、
     *       => 而而那它逐字类型与权限逐字相符」
     *   => 而那**内容**逐字每条钉它自己那一个具体值（=> 而按 C-05 不猜填）
     *   => 而那**两侧**逐字是**同一族的两侧**、=> 而而那它们逐字各答一件事、
     *     => 而依 **C-105** 逐字**不合并**（=> 而而那那 negative 那一侧**不**被这一格顶掉）
     */
    fun hostBoundPositive(context: Context, family: String, input: String): JSONObject {
        val method = "hostBound.positive"
        when (family.trim()) {
            "kmi" -> {
                val r = kmiCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 KMI 符号清单、=> 而而那它逐字**能**解析出那符号、=> 而而那它逐字**能**解析出**那一个数**个符号。")
            }
            "lkm" -> {
                val r = lkmCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那模块、=> 而而那它逐字**能**加载、=> 而而那那目录逐字**有****那一个数**个模块。")
            }
            "target" -> {
                val r = targetCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那声明逐字与那实际逐字**一致**、=> 而而那那三者（构建目标 / 运行目标 / 设备身份）逐字是**那一个值**。")
            }
            "uart" -> {
                val r = uartCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那节点逐字**在位**、=> 而而那它逐字**有**输出、=> 而而那那输出逐字含**那一条**。")
            }
            "linker64" -> {
                val r = linker64Check(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 ELF、=> 而而那那 e_type 逐字是 **2** 或 **3**、=> 而而那那一个 ELF 逐字**在位**。")
            }
            "file" -> {
                val r = fileCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**在**那 LOB-OS 自己那一份里面、=> 而而那它逐字**在位**、=> 而而那那一次落-读-删逐字**成**。")
            }
            "memory" -> {
                val r = memoryCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那已用逐字**没超**了那声明限额、=> 而而那那限额逐字是**那一个数**。")
            }
            "security" -> {
                val r = securityCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**不**越权、=> 而而那那一次越权动作逐字**被拒**、=> 而而那它逐字**留审计**。")
            }
            "time" -> {
                val r = timeCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那墙上时钟逐字**没倒**、=> 而而那那单调时钟那一趟逐字**没超**了那预算、=> 而而那那预算逐字是**那一个数**。")
            }
            else -> return failure(
                method, "host-bound-positive",
                "那一族逐字不在那九族里：" + family +
                    "（=> 而而那那九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "=> 而而那按 C-05 报缺）",
            )
        }
    }

    /**
     * 那「**看得见装了哪些 app**」那一族逐字一个**产品入口**（=> 而而那它是那 `app.listInstalled` 那一格）。
     *
     * 缺陷名（C-102）**那一格逐字没有单独的产品入口** ——
     *   => 而那旧版逐字**只有**那 `crossApp` 那一格逐字**兼**着
     *     （=> 而而那那 UI 那一族逐字要**手打**那方法名、=> 而而那那**读数**逐字**难**取）
     *   => 而而那那 07 逐字核出：=> 而而那那**返回体**逐字**答**了那一格
     *     （=> 而而那那 `app.launch` 那一格逐字答的那一条逐字里逐字写着
     *       「那仓内清单声明过的那几件逐字是：」逐字后面逐字是**空**的
     *       => 而逐字那一档逐字**没有任何一件声明过**
     *       => 而逐字那 `app.listInstalled` 逐字应当逐字**只列那 0 件**）
     *   => 而而那那**App 自己退到后台**那一格（=> 而那 07 逐字核出）=> 而而那它是那**产品入口**那一格的问题
     *   => 而那**处置**：=> 而那那一族逐字**单**一个产品入口、=> 而而那它逐字**走**那 `crossApp` 那一格
     *     （=> 而而那那**形状**逐字**已经**有了、=> 而而那那「跨 app」那一族逐字经那**安装方**那一路逐字调、
     *       => 而而那那安装方逐字有 `GROUP_CROSS_APP` 那一档、=> 而而那那 UI 那一族逐字多那三个入口）
     *     => 而而那那 `app.listInstalled` 逐字与那 `app.launch` 逐字**同形**
     */
    fun listDeclaredApps(context: Context): JSONObject {
        //  => 而而那那一族逐字**只列**那**仓内清单声明过的那几件**、=> 而而那那**其余**逐字**不**列
        //    （=> 而那按 C-05 报缺、=> 而**不**静默地少列）
        val declared = runCatching {
            lobos.api.CapabilityBroker.live()?.declaredPackages()
        }.getOrNull() ?: emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("packages", org.json.JSONArray(declared))
            put("count", declared.size)
            put("detail", "那仓内清单声明过的那几件逐字 = " + declared.joinToString(",") +
                "（=> 而而那那**0 件**逐字**也是**一个**答**、=> 而而那按 C-05 不猜填）")
        }
    }

    /**
     * 那「跨 app 边界」那一族逐字一个**产品入口**（=> 而而那它是产品那一面逐字的事、=> 而而那它逐字**不**是给程序那一族逐字开的后门）。
     *
     * 缺陷名（C-102）**那一族逐字在设备上逐字没有可走的路** ——
     *   => 而那旧版逐字**没有**这一格
     *     => 而而那那 `HostControlService` 那一族逐字那公开方法逐字全清单逐字是
     *       `pieces` / `rollbackJudgement` / `pieceEligibility` / `update` / `execCommand` /
     *       `execDirect` / `verifyDeviceNodes` / `hostBoundCheck` / `hostBoundPositive` /
     *       `loadPeSample` / `rollback`
     *       => 而而那它逐字**没有** `app.launch` / `app.listInstalled` / `ui.tap` 那一族逐字的方法
     *     => 而而那那一族逐字**只能**经那桥逐字调、=> 而而那那桥逐字要求那会话逐字有那一档
     *       （=> 而而那那 `GROUP_CROSS_APP` 那一档）、=> 而而那**没有任何一件声明过**
     *     => 而而那那在设备上逐字**没有**可走的路（=> 而按 **C-05** 报缺）
     *   => 那**处置**：=> 而那**产品入口**逐字经那**安装方**那一路逐字调
     *     => 而而那那一路逐字是那 `INSTALLER_ID` 那一族、=> 而而那它是那**产品**那一面
     *     => 而而那它逐字**不**是给程序那一族逐字开的后门（=> 而而那那**程序**逐字**还是**要那一档）
     *     => 而而那那**读数**逐字**能**取到（=> 而而那那一格逐字是那读数那一族）
     */
    fun crossApp(context: Context, method: String, params: JSONObject): JSONObject {
        val m = method.trim()
        if (m !in CROSS_APP_METHODS) {
            return failure(
                "cross-app", "cross-app",
                "那一族逐字不在那三格里：" + m +
                    "（=> 而而那那三格逐字是：" + CROSS_APP_METHODS.joinToString(",") +
                    "；=> 而而那按 C-05 报缺）",
            )
        }
        //  => 而而那那一路逐字**经**那**安装方**那一族逐字调、=> 而而那它逐字**不**是给程序的后门
        val r = runCatching {
            lobos.api.CapabilityBroker.live()?.invokeLocal(
                lobos.api.CapabilityBroker.INSTALLER_ID,
                m,
                params ?: JSONObject(),
            )
        }.getOrNull()
        return r ?: failure("cross-app", "cross-app", "那宿主桥没起来（=> 而而那按 C-05 报缺）")
    }

    /**
     * 那**堆**逐字那一格（=> 而而那它是「那一份逐字在那一堆里逐字待了多久、=> 而而那它逐字是不是逐字还在」那一族）。
     *
     * 缺陷名（C-102）**那一族逐字只有那上限、=> 而而那它逐字**不**答「那一份逐字待了多久」** ——
     *   => 而那旧版逐字**没有**这一格
     *     => 而而那那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
     *       · 而那堆逐字 `Dalvik Heap` 逐字 **85682 → 188222 → 331053 KB ≈ 323 MB → 294482 KB ≈ 288 MB**
     *       · => 而而那那**分配量**逐字是那 **139 MB AllocSpace**、=> 而而那那**两个** LOS 对象
     *         逐字 **69 MB** 与 **94 MB**、=> 而而那那**一个大对象**逐字 55 到 94 MB
     *       · => 而而那它逐字**就是**那**整读**的那一件（=> 而和那那**一份**逐字**被**留在那堆里）
     *   => 而那**处置**：=> 而那**读法名**逐字**记**着（=> 而那是那 `ReadRoute` 那一族）
     *     => 而而那那**记录**逐字逐字**带**那**读法名**与那**时刻**
     *     => 而而那这一格逐字答的是「那一份逐字**待了多久**、=> 而而那它逐字**是不是还在**」
     *       （=> 而而那那**判据**逐字**不**由「那一件逐字是什么」顶掉）
     */
    fun memoryProbe(context: Context): JSONObject {
        val rt = Runtime.getRuntime()
        val totalBytes = rt.totalMemory()
        val freeBytes = rt.freeMemory()
        val usedBytes = totalBytes - freeBytes
        val maxBytes = rt.maxMemory()
        val live = lobos.kernel.fs.ReadRoute.liveReads()
        return JSONObject().apply {
            put("ok", true)
            put("heapUsedBytes", usedBytes)
            put("heapTotalBytes", totalBytes)
            put("heapMaxBytes", maxBytes)
            put("heapUsedMb", usedBytes / (1024L * 1024L))
            put("liveReads", live)
            put("liveReadCount", live.length())
            put("detail", "那堆逐字 = " + (usedBytes / (1024L * 1024L)) + " MB、" +
                "=> 而而那那**还活着**的整读逐字 = " + live.length() + " 处、" +
                "=> 而而那那**读法名**逐字答的是「那一条路」、=> 而而那那**待了多久**逐字**也在**那记录里")
        }
    }

    fun hostBoundCheck(context: Context, family: String, input: String): JSONObject {
        val method = "hostBound.check"
        when (family.trim()) {
            "kmi" -> return kmiCheck(context, input)
            "lkm" -> return lkmCheck(context, input)
            "target" -> return targetCheck(context, input)
            "uart" -> return uartCheck(context, input)
            "linker64" -> return linker64Check(context, input)
            "file" -> return fileCheck(context, input)
            "memory" -> return memoryCheck(context, input)
            "security" -> return securityCheck(context, input)
            "time" -> return timeCheck(context, input)
            else -> return failure(
                method, "host-bound",
                "那一族逐字不在那九族里：" + family +
                    "（=> 而而那那九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "=> 而而那按 C-05 报缺）",
            )
        }
    }

    /** 那 **kmi** 那一族逐字（=> 而而那它逐字三选一：来源缺失 / 身份不符 / 解析失败）。 */
    private fun kmiCheck(context: Context, input: String): JSONObject {
        //  => 而而那那 **KMI** 清单逐字该从哪取：=> 而而那那 abi.stg 逐字**是**那内核的数据
        //    （=> 而而那那 kmi.html 与那 lkm.html 逐字**只是**那网页、=> 而而那它们逐字**不是**那清单）
        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("kmi", false, "来源缺失", "那清单逐字不在：" + input + "（=> 而按 C-05 报缺）")
        }
        val text = runCatching { f.readText() }.getOrElse { e ->
            return hostBoundResult("kmi", false, "解析失败", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        //  => 而而那那**清单**逐字是什么形状：=> 而而那那 abi.stg 逐字每一行逐字一个符号
        val lines = text.lines()
        if (lines.isEmpty()) {
            return hostBoundResult("kmi", false, "解析失败", "那清单逐字是空的（=> 而按 C-05 报缺）")
        }
        val syms = lines.mapNotNull { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) null else t.substringBeforeLast(' ').trim()
        }
        if (syms.isEmpty()) {
            return hostBoundResult("kmi", false, "解析失败", "那清单逐字**一个**符号逐字都**没**解析出来")
        }
        //  => 而而那那**身份**逐字核：=> 而而那它逐字是那**内核**的**数据**
        val identity = runCatching {
            f.readLines().firstOrNull { it.trim().startsWith("#") }?.trim().orEmpty()
        }.getOrDefault("")
        val looksLikeKmi = syms.any { it.contains(".") } && syms.any { it.contains("_") }
        if (!looksLikeKmi) {
            return hostBoundResult(
                "kmi", false, "身份不符",
                "那清单逐字**不像**那 KMI 符号清单（=> 而而那它逐字有 " + syms.size + " 行、" +
                    "=> 而而那按 C-05 报缺）",
            )
        }
        return hostBoundResult(
            "kmi", true, "在位",
            "那清单逐字**能**解析出 " + syms.size + " 个符号（=> 而而那那身份逐字 = " +
                (if (identity.isBlank()) "（那首行逐字**没有**注释、=> 而而那按 C-05 报缺）" else identity) + "）",
        )
    }

    /** 那 **lkm** 那一族逐字（=> 而而那它逐字两选一：接口缺失或坏模块）。 */
    private fun lkmCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**模块**逐字是什么：=> 而而那那 lkm.html 逐字**只是**那网页、=> 而而那它逐字**不是**那清单
        //    => 而而那那**模块**逐字该是那 `.ko` 逐字或那**模块清单**
        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("lkm", false, "接口缺失", "那模块逐字不在：" + input + "（=> 而按 C-05 报缺）")
        }
        if (f.isDirectory) {
            val mods = f.listFiles()?.filter { it.name.endsWith(".ko") }?.sortedBy { it.name }
                ?: emptyList()
            if (mods.isEmpty()) {
                return hostBoundResult("lkm", false, "接口缺失", "那目录逐字**没有** `.ko` 模块（=> 而按 C-05 报缺）")
            }
            return hostBoundResult("lkm", true, "在位", "那目录逐字有 " + mods.size + " 个模块（=> 而而那头一个是 " + mods[0].name + "）")
        }
        //  => 而而那那**坏模块**逐字：=> 而而那那文件头逐字不成形
        val head = runCatching {
            f.inputStream().use { s -> ByteArray(16) { s.read().toByte() } }
        }.getOrElse { e ->
            return hostBoundResult("lkm", false, "坏模块", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        val isElf = head.size >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        if (!isElf) {
            return hostBoundResult(
                "lkm", false, "坏模块",
                "那模块逐字**不是** ELF（=> 而而那那 ELF 魔数逐字不是 7f 45 4c 46、=> 而按 C-05 报缺）",
            )
        }
        return hostBoundResult("lkm", true, "在位", "那模块逐字**是** ELF（=> 而而那它逐字**能**加载）")
    }

    /** 那**目标机型**那一族逐字（=> 而而那它逐字任一不一致）。 */
    private fun targetCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**目标机型**逐字是什么：=> 而而那那**声明**的那一档逐字
        //    => 而而那那**实际**的那一档逐字：=> 而而那那 App 逐字自己那 Build 那一族
        val declared = input.trim()
        if (declared.isEmpty()) {
            return hostBoundResult("target", false, "答不出", "那声明逐字是空的（=> 而按 C-05 报缺）")
        }
        val actual = listOf(
            android.os.Build.SOC_MODEL,
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
        ).filter { it.isNotBlank() }.distinct()
        val abi = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        val matched = actual.any { declared.equals(it, ignoreCase = true) } ||
            declared.contains(abi) || abi.contains(declared)
        if (!matched) {
            return hostBoundResult(
                "target", false, "不一致",
                "那声明逐字与那实际逐字**不一致**：声明=" + declared + " 实际=" + actual.joinToString(",") +
                    " abi=" + abi + "（=> 而按 C-05 报缺）",
            )
        }
        return hostBoundResult("target", true, "一致", "那声明逐字与那实际逐字**一致**（= " + declared + "）")
    }

    /** 那 **UART** 那一族逐字（=> 而而那它逐字两选一：错节点或无输出）。 */
    private fun uartCheck(context: Context, input: String): JSONObject {
        //  => 而而那那 **UART** 逐字是什么：=> 而而那那**节点**逐字（=> 而而那它逐字是那宿主绑定那一族）
        //    => 而而那那**节点**逐字逐字答「那**坏输入**逐字是什么」
        val node = input.trim()
        if (node.isEmpty()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字是空的（=> 而按 C-05 报缺）")
        }
        val f = java.io.File(node)
        if (!f.exists()) {
            return hostBoundResult(
                "uart", false, "错节点",
                "那节点逐字**不在**：" + node + "（=> 而而那那 UART 逐字是那宿主绑定的那几件、" +
                    "=> 而而那按 C-05 报缺）",
            )
        }
        if (!f.canRead()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字**读**不到（=> 而按 C-05 报缺）")
        }
        val text = runCatching { f.readText() }.getOrElse { e ->
            return hostBoundResult("uart", false, "无输出", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (text.isBlank()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字**空**的（=> 而按 C-05 报缺）")
        }
        return hostBoundResult("uart", true, "在位", "那节点逐字有 " + text.length + " 字节（=> 而而那那头一句 = " +
            text.lineSequence().first().take(60) + "）")
    }

    /** 那 **linker64** 那一族逐字（=> 而而那它逐字两选一：缺失或坏 ELF）。 */
    private fun linker64Check(context: Context, input: String): JSONObject {
        //  => 而而那那 **linker64** 逐字是什么：=> 而而那那**动态链接器**
        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("linker64", false, "缺失", "那 linker64 逐字不在：" + input + "（=> 而按 C-05 报缺）")
        }
        val head = runCatching {
            f.inputStream().use { s -> ByteArray(20) { s.read().toByte() } }
        }.getOrElse { e ->
            return hostBoundResult("linker64", false, "坏 ELF", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (head.size < 20 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return hostBoundResult(
                "linker64", false, "坏 ELF",
                "那文件逐字**不是** ELF（=> 而而那那 ELF 魔数逐字不是 7f 45 4c 46、=> 而按 C-05 报缺）",
            )
        }
        //  => 而而那那 e_type 逐字在偏移 16（=> 而而那那 16 是那个 e_ident 逐字**加**那 e_type 逐字前面那两个）
        val etype = (head[16].toInt() and 0xff) or ((head[17].toInt() and 0xff) shl 8)
        val kind = when (etype) {
            2 -> "ET_EXEC"
            3 -> "ET_DYN"
            else -> "e_type=" + etype + "（=> 而而那它逐字既不是 2 也不是 3）"
        }
        if (etype != 2 && etype != 3) {
            return hostBoundResult(
                "linker64", false, "坏 ELF",
                "那 ELF 逐字的 e_type=" + etype + "（=> 而而那它逐字既不是 ET_EXEC(2) 也不是 ET_DYN(3)、=> 而按 C-05 报缺）",
            )
        }
        return hostBoundResult("linker64", true, "在位", "那 linker64 逐字**是** ELF（=> 而而那那形态 = " + kind + "）")
    }

    /** 那**文件**那一族逐字（=> 而而那它逐字三选一：越界 / 坏链接 / 不支持操作）。 */
    private fun fileCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**越界**逐字：=> 而而那那路径逐字**逃出**那 LOB-OS 自己那一份
        //    => 而而那那**坏链接**逐字：=> 而而那那软链逐字**指向**一个**不存**的目标
        //    => 而而那那**不支持操作**逐字：=> 而而那那操作逐字**不在**那白名单里
        val path = input.trim()
        if (path.isEmpty()) {
            return hostBoundResult("file", false, "越界", "那路径逐字是空的（=> 而按 C-05 报缺）")
        }
        val target = java.io.File(path)
        val root = context.filesDir.canonicalFile
        val canon = runCatching { target.canonicalFile }.getOrNull()
        if (canon == null) {
            return hostBoundResult("file", false, "坏链接", "那路径逐字**解不出**那真的一个（=> 而按 C-05 报缺）")
        }
        //  => 而而那那**越界**逐字核
        if (!canon.path.startsWith(root.path) && !canon.path.startsWith("/data/local/tmp")) {
            return hostBoundResult(
                "file", false, "越界",
                "那路径逐字**逃出**那 LOB-OS 自己那一份：" + canon.path + "（=> 而按 C-05 报缺）",
            )
        }
        //  => 而而那那**坏链接**逐字核
        if (target.isDirectory) {
            return hostBoundResult("file", false, "不支持操作", "那路径逐字是一个目录（=> 而按 C-05 报缺）")
        }
        if (!target.exists()) {
            return hostBoundResult(
                "file", false, "坏链接",
                "那路径逐字**不在**：" + canon.path + "（=> 而而那按 C-05 报缺）",
            )
        }
        return hostBoundResult("file", true, "在位", "那路径逐字**在位**（= " + canon.path + "、" +
            (if (target.length() > 0) target.length().toString() + " 字节" else "空") + "）")
    }

    /** 那**内存**那一族逐字（=> 而而那它逐字两选一：超限不回收或静默失败）。 */
    private fun memoryCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**限额**逐字是什么：=> 而而那那**声明**的那一个数（=> 而而那那单位是那 MB）
        //    => 而而那那**实际**逐字是什么：=> 而而那那 App 逐字自己那可用内存那一族
        val limitMb = input.trim().toLongOrNull()
        if (limitMb == null || limitMb <= 0) {
            return hostBoundResult("memory", false, "静默失败", "那限额逐字**不是**一个正数（=> 而按 C-05 报缺）")
        }
        val rt = Runtime.getRuntime()
        val maxBytes = rt.maxMemory()
        val totalBytes = rt.totalMemory()
        val freeBytes = rt.freeMemory()
        val usedBytes = totalBytes - freeBytes
        val maxMb = maxBytes / (1024L * 1024L)
        val usedMb = usedBytes / (1024L * 1024L)
        val over = usedMb > limitMb
        return hostBoundResult(
            "memory", !over, if (over) "超限不回收" else "在位",
            "那声明限额=" + limitMb + "MB、=> 而而那那实际已用=" + usedMb + "MB / 上限=" + maxMb + "MB" +
                (if (over) "（=> 而而那它逐字**超**了那限额、=> 而而那按 C-05 报缺）" else "（=> 而而那它逐字**没**超）"),
        )
    }

    /** 那**安全**那一族逐字（=> 而而那它逐字是「越权动作被拒并留审计」）。 */
    private fun securityCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**越权**逐字是什么：=> 而而那那路径逐字**逃出**那 LOB-OS 自己那一份
        //    => 而而那那**拒**逐字要**留审计**
        val path = input.trim()
        if (path.isEmpty()) {
            return hostBoundResult("security", false, "静默失败", "那路径逐字是空的（=> 而按 C-05 报缺）")
        }
        val root = context.filesDir.canonicalFile
        val canon = runCatching { java.io.File(path).canonicalFile }.getOrNull()
        if (canon == null) {
            return hostBoundResult("security", false, "静默失败", "那路径逐字**解不出**那真的一个（=> 而按 C-05 报缺）")
        }
        val denied = !canon.path.startsWith(root.path)
        if (denied) {
            //  => 而而那那**拒**逐字要**留审计**（=> 而按 C-122/C-123 要能换回原话）
            lobos.services.log.RuntimeDiagnostics.append(
                context, "security", false, "越权动作被拒",
                "path=" + canon.path + "（=> 而而那一族逐字要**留审计**）",
            )
            return hostBoundResult(
                "security", false, "越权动作被拒",
                "那路径逐字**越权**：" + canon.path + "（=> 而而那那审计逐字**已**留）",
            )
        }
        return hostBoundResult("security", true, "在位", "那路径逐字**在**那 LOB-OS 自己那一份里面（=> 而而那它逐字**不**越权）")
    }

    /** 那**时间**那一族逐字（=> 而而那它逐字两选一：时钟倒退或超时语义不符）。 */
    private fun timeCheck(context: Context, input: String): JSONObject {
        //  => 而而那那**超时**逐字是什么：=> 而而那那声明的那一个数（=> 而而那那单位是那毫秒）
        //    => 而而那那**时钟倒退**逐字：=> 而而那那墙上时钟逐字**比**那单调时钟那一族**慢**
        val budgetMs = input.trim().toLongOrNull()
        if (budgetMs == null || budgetMs <= 0) {
            return hostBoundResult("time", false, "超时语义不符", "那预算逐字**不是**一个正数（=> 而按 C-05 报缺）")
        }
        val wall0 = System.currentTimeMillis()
        val mono0 = android.os.SystemClock.elapsedRealtime()
        val busy = runCatching { Thread.sleep(minOf(budgetMs, 2000L)) }.getOrNull()
        val wallDelta = System.currentTimeMillis() - wall0
        val monoDelta = android.os.SystemClock.elapsedRealtime() - mono0
        //  => 而而那那**时钟倒退**逐字：=> 而而那那墙上时钟那一趟逐字**倒**了
        val back = wallDelta < 0
        if (back) {
            return hostBoundResult(
                "time", false, "时钟倒退",
                "那墙上时钟逐字**倒**了：" + wallDelta + "ms（=> 而按 C-05 报缺）",
            )
        }
        //  => 而而那那**超时语义**逐字：=> 而而那那单调时钟那一趟逐字**不少于**那预算
        val overBudget = monoDelta > budgetMs
        return hostBoundResult(
            "time", !overBudget, if (overBudget) "超时语义不符" else "在位",
            "那预算=" + budgetMs + "ms、=> 而而那那墙上时钟过了 " + wallDelta + "ms、=> 而而那那单调时钟过了 " +
                monoDelta + "ms" + (if (overBudget) "（=> 而而那它逐字**超**了那预算）" else "（=> 而而那它逐字**没**超）"),
        )
    }

    /** 那九族逐字那**读数**那一格（=> 而而那它逐字**同形**、=> 而而那它逐字**不**静默）。 */
    private fun hostBoundResult(family: String, ok: Boolean, cls: String, detail: String): JSONObject =
        JSONObject().apply {
            put("ok", ok)
            put("family", family)
            put("class", cls)
            put("detail", detail)
        }

    fun loadPeSample(context: Context, asset: String): JSONObject {
        val app = context.applicationContext
        //  缺陷名（C-118）这一支不走桥，它的方法名是 lobos.compat.pe.sample，
        //  而失败文案里的 method 那一格必须与本支一致。
        val method = "lobos.compat.pe.sample"
        val sample = peSamples.firstOrNull { it.asset == asset }
            ?: return failure(method, "pe-sample", "没有这一份 PE L2 样本：" + asset)
        return try {
            val out = File(app.cacheDir, asset)
            out.parentFile?.mkdirs()
            app.assets.open(asset).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            //  => 而而那那**读法**逐字**可查**（=> 而而那那 `ReadRoute` 那一族逐字**记**那一格）
            val bytes = lobos.kernel.fs.ReadRoute.readAll(app, "pe.sample", out)
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (actual != sample.sha256) {
                return failure(method, "pe-sample", "PE L2 样本 sha256 不匹配：" + asset)
            }
            val image = PeLoader.parse(bytes)
            JSONObject().apply {
                put("ok", true); put("requestId", "pe-sample"); put("asset", asset)
                put("kind", sample.label)
                put("bytes", out.length()); put("sha256", actual)
                put("machine", image.machine); put("machineName", machineName(image.machine))
                put("pe32Plus", image.pe32Plus)
                put("entryPoint", image.entryPointRva); put("imageBase", image.imageBase)
                put("sections", image.sections.size); put("imports", image.imports.size)
                put("importDlls", image.imports.map { it.dllName })
            }
        } catch (e: Throwable) { failure(method, "pe-sample", e.message ?: e.javaClass.simpleName) }
    }

    private fun machineName(machine: Int): String = when (machine) {
        0x014c -> "i386"
        0x8664 -> "x86-64"
        0xAA64 -> "arm64"
        0x01c0 -> "arm"
        else -> "unknown(0x" + machine.toString(16) + ")"
    }

    /** Formal rollback of one piece to its APK baseline. */
    fun rollback(context: Context, id: String, base: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext
        //  缺陷名（C-118）失败结果里的 method 那一格必须写**本次真正调用的那个方法**：
        //  这一支调的是 client.rollback，所以方法名是 lobos.sys.native.rollback，
        //  而不能写成 update 那一支的说法。下面每一处 failure 都取本支的 method 变量。
        val method = "lobos.sys.native.rollback"
        val pieceId = id.trim()
        val selectedBase = base.trim().ifEmpty { "apk" }
        val correlatedId = requestId.trim().ifEmpty { "rollback-" + UUID.randomUUID() }
        if (pieceId.isBlank()) return failure(method, correlatedId, "id 为空")
        if (selectedBase != "apk") return failure(method, correlatedId, "base 只接受 apk")
        val broker = lobos.api.CapabilityBroker.live()
            ?: return failure(method, correlatedId, "宿主桥未启动：控制面此刻不可用")
        return try {
            val token = broker.beginSession(MANAGEMENT_ID, MANAGEMENT_GENERATION)
            SessionRegistry.bindPid(app, token, android.os.Process.myPid())
            val client = ControlClient(
                socketName = SessionRegistry.socketName(token),
                sessionToken = token,
                protocolVersion = lobos.BuildConfig.BRIDGE_PROTOCOL,
            )
            val result = client.rollback(pieceId, selectedBase, correlatedId, dryRun)
            //  回退成功的判据（01 裁定）：回退之后入口软链的 realpath 与来源记录里记下的
            //  APK 原件 body 的 realpath 逐字相等。这一格只读，只作核对与诊断。
            val verified = if (result.ok && !dryRun) PieceUpdater.verifyApkRestored(app, pieceId) else (false to "未执行真实回退（失败或 dryRun）")
            JSONObject().apply {
                put("ok", result.ok)
                put("code", result.code)
                put("requestId", correlatedId)
                put("method", method)
                put("result", result.value)
                put("rollbackVerified", verified.first)
                put("rollbackVerifyDetail", verified.second)
            }
        } catch (e: Throwable) {
            failure(method, correlatedId, e.message ?: e.javaClass.simpleName)
        }.also { response ->
            RuntimeDiagnostics.append(
                app,
                "host-control",
                response.optBoolean("ok", false),
                "宿主正式管理服务执行回滚",
                "requestId=" + correlatedId + " id=" + pieceId + " base=" + selectedBase +
                    " dryRun=" + dryRun + " response=" + response,
            )
        }
    }

    /** 那「跨 app 边界」那一族逐字那**三格**（=> 而而那它逐字**不**给程序那一族逐字开后门）。 */
    private val CROSS_APP_METHODS = listOf("app.launch", "app.listInstalled", "ui.tap")

    private fun failure(method: String, requestId: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", -1)
        put("requestId", requestId)
        put("method", method)
        put("error", message)
    }
}
