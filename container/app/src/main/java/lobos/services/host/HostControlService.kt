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
 * Host-resident formal management service of LOB-OS: the product-internal control plane entry. The host owns it, so the
 * management UI inside the APK can call it without an installed quick app. Authorization: fixed host management identity
 * (lobos.installer), which the broker treats as a system-scope session. Audit: the broker log records each requestId and
 * this service writes a host-control diagnostic per call. Transport: still crosses the formal LocalSocket handshake via
 * ControlClient, so token, framing, timeout, structured errors and idempotency all apply. */
object HostControlService {
    const val MANAGEMENT_ID = "lobos.installer"
    const val MANAGEMENT_GENERATION = 1L

    /**
     * List the pieces with their entry origin (APK original or OTA version) and each one's rollback eligibility.
     * 缺陷 C-945：件列表原先只有来源记录那几格，没有回退资格与理由，而「能不能回退」是操作者最先要看的一格。
     * 主体是所有在册的件（ProgramIndex 全部登记项）；「在册」由注册表答、「有来源记录」由来源记录答，所以来源字段缺失时是空串、
     *   回退理由答「无来源指针」。
     * C-945 八个字段都有真实消费者（本文件 JSON 与 HostControlActivity 下拉标签/逐字段明细）：origin 为 active（apk/ota）、apkVersion、
     *   apkTarget（apkSlots 的 entryPath）、otaVersion、otaTarget（otaSlots 的 entryPath）、hasApkOriginal、hasOta、updated。
     */
    fun pieces(context: Context): JSONArray {
        val app = context.applicationContext
        val origins = PieceOriginStore.all(app).associateBy { it.id }
        val arr = JSONArray()
        for (s in PieceUpdater.states(app)) {
            val o = origins[s.id]
            //  缺陷名 C-102：回退资格原先在两处各写一套（控制面按「入口是不是软链」判接管、回退本身按来源记录判），
            //    两处会给出不同答案（UI 说可回滚、回退时答未接管）。现在只有一处判据（`PieceUpdater.rollbackStatus`）。
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
     * 判据只有一处：`PieceUpdater.rollbackStatus`（回退那族唯一的判据来源）；01 linux-architect 裁定；
     * 回退主体集合是所有在册的件（C-954）。理由五种：可回滚 / 未接管（F2）/ 无来源指针（F1）/
     * 库形态入口未定位（F3）/ 非在册。
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
        //  缺陷名 C-118：失败结果里的 method 必须写本次真正调用的那个方法——这一支调 client.update，
        //  所以是 lobos.sys.native.update；下面每处 failure 都取本支的 method 变量，不写死字面量。
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
     * 在 LOB-OS 自己那一份 Linux 里跑一条命令（产品那一面）。
     * 缺陷 C-102：旧版「跑一条用户指定的命令」没有合法的产品入口——只有 `ControlClient.exec`（客户端那族、要带 token 的 handshake）：
     *   `LobosBridge.invoke` 拒 SCOPE_SYSTEM（`sys.tty.exec` 是系统作用域），`QuickAppLaunchActivity` 那支只能用 `am start` 带 extra 叫起来。
     *   而 `invokeLocal` 能调 `sys.tty.exec`（INSTALLER_ID 放行、serverGranted 给 installer 全部含 GROUP_SYS，不经 socket、不 claim 会话）。
     * 处置：这一格就是产品 UI 上的一个入口（走 `invokeLocal`、不走 token 那族）。
     * @param cmd 要跑的命令串；@param timeoutMs 超时（默认 10000） */
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
     * 不经 shell 直接调那一条（M7-05 要的那条产品入口）。
     * 缺陷 C-102：旧版在设备上测不了 M7-05——唯一能跑命令的入口是 `execCommand`（走 `sys.tty.exec` 的 `bash -c <命令>`），bash 去 execve
     *   那个坏 ELF、报的是 bash 自己那条，而不是产品那族该给的「入口不是可执行的形态（既不是 ELF 也不是 #!）—— 拒绝调起，不当程序跑：
     *   <路径>」。处置：这一格不经 shell，直接交给 `ProcessSupervisor`（认不出那一档时报缺、退出码是那个数、诊断进 stderr）。
     * 缺陷 C-102（带参数）：旧版 `execDirect(context, path, timeoutMs)` 的 argv 只有那一条，git 没有 `--version` 就打 usage、退出码 1
     *   （07 实测 stdout 是 git 的 usage 原文）。处置：argv 带上参数并透给 `LocalExec.run`；形态仍由 `ExecEntry.argv` 算。 */
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
        //  不经 shell：argv 只有那一条与那些参数；形态仍由 `ExecEntry.argv` 那族算（不变）。
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
     * 被要求建一个错形状的那一条（M7-12 要的那条产品入口）。
     * 缺陷 C-102：旧版在设备上测不了 M7-12——没有这一格，`SysDev.render` 先 `wipe(root)` 再建、之后才 `verifyNodes`，
     *   产品永远看不到盘上现存的错形状（每次都被自己清掉了）。
     * 处置：这一格在渲染之前核盘上现状，答的是那三类之一（不只报「失败」）；它不是探针，是产品界面上的一个入口。
     */
    fun verifyDeviceNodes(context: Context): JSONObject {
        val method = "sysdev.verify"
        val root = lobos.kernel.sysdev.SysDev.root(context)
        if (!root.isDirectory) {
            return failure(method, "sysdev-verify", "那设备节点目录逐字不在：" + root.absolutePath + "（=> 而按 C-05 报缺）")
        }
        val bad = lobos.kernel.sysdev.SysDev.verifyNodes(root)
        //  全部不合的那几处（不只报第一处，一处不许盖住其余几处）
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
                //  报出哪一组 rwx 不一样（Lead 要的那一格）：只在「权限过宽」那一档才答。
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
     * 九族每族一个产品入口（「产品那面有没有九族的入口」那一族）。
     * 缺陷 C-102：旧版没有能吃到坏输入的入口——公开方法只有 `pieces` / `rollbackJudgement` / `pieceEligibility` / `update` / `execCommand` / `execDirect` /
     *   `verifyDeviceNodes` / `loadPeSample` / `rollback`，没有 kmi / lkm / UART / 目标机型那族；`files/etc` 没有 `kmi.html` / `lkm.html` / `abi.stg`。
     * 九族：kmi 三选一 / lkm 两选一 / 目标机型任一不一致 / UART 两选一 / linker64 两选一 / 文件三选一 / 内存两选一 / 安全越权被拒并留审计 / 时间两选一。
     *   处置：每族一个入口（与 `execDirect` 同形），答「坏输入被吃进去后报出什么」并报出原话（C-122/C-123）、不静默（C-126）。
     * positive 那一侧：缺陷 C-102 旧版只有 negative（`hostBoundCheck`），07 取不到 positive 那一读；形状照 M7-05（合法 ELF 跑起来、退出码是期望值、
     *   stderr 空）与 M7-12（合法那类建得出来、类型与权限相符），每条钉它自己那个具体值（C-05 不猜填），两侧依 C-105 不合并。
     */
    fun hostBoundPositive(context: Context, family: String, input: String): JSONObject {
        val method = "hostBound.positive"
        when (family.trim()) {
            "kmi" -> {
                val r = kmiCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 KMI 符号清单、=> 而那它逐字**能**解析出那符号、=> 而那它逐字**能**解析出**那一个数**个符号。")
            }
            "lkm" -> {
                val r = lkmCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那模块、=> 而那它逐字**能**加载、=> 而那目录逐字**有****那一个数**个模块。")
            }
            "target" -> {
                val r = targetCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那声明逐字与那实际逐字**一致**、=> 而那三者（构建目标 / 运行目标 / 设备身份）逐字是**那一个值**。")
            }
            "uart" -> {
                val r = uartCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那节点逐字**在位**、=> 而那它逐字**有**输出、=> 而那输出逐字含**那一条**。")
            }
            "linker64" -> {
                val r = linker64Check(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 ELF、=> 而那 e_type 逐字是 **2** 或 **3**、=> 而那一个 ELF 逐字**在位**。")
            }
            "file" -> {
                val r = fileCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**在**那 LOB-OS 自己那一份里面、=> 而那它逐字**在位**、=> 而那一次落-读-删逐字**成**。")
            }
            "memory" -> {
                val r = memoryCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那已用逐字**没超**了那声明限额、=> 而那限额逐字是**那一个数**。")
            }
            "security" -> {
                val r = securityCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**不**越权、=> 而那一次越权动作逐字**被拒**、=> 而那它逐字**留审计**。")
            }
            "time" -> {
                val r = timeCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那墙上时钟逐字**没倒**、=> 而那单调时钟那一趟逐字**没超**了那预算、=> 而那预算逐字是**那一个数**。")
            }
            else -> return failure(
                method, "host-bound-positive",
                "那一族逐字不在那九族里：" + family +
                    "（=> 而那九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "=> 而那按 C-05 报缺）",
            )
        }
    }

    /**
     * 「看得见装了哪些 app」那一族一个产品入口（即 `app.listInstalled` 那一格）。
     * 缺陷 C-102：旧版没有单独的产品入口——只有 `crossApp` 兼着（UI 要手打方法名、读数难取）。07 核出：返回体答了那一格（`app.launch`
     *   的返回里「那仓内清单声明过的那几件是：」后面是空的，即没有一件声明过，应只列 0 件）；「App 自己退到后台」那格也是产品入口问题。
     * 处置：单一个产品入口、走 `crossApp`（形状已有，「跨 app」经安装方那一路调、安装方有 `GROUP_CROSS_APP`），与 `app.launch` 同形。
     */
    fun listDeclaredApps(context: Context): JSONObject {
        //  只列仓内清单声明过的那几件、其余不列（按 C-05 报缺，不静默地少列）
        val declared = runCatching {
            lobos.api.CapabilityBroker.live()?.declaredPackages()
        }.getOrNull() ?: emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("packages", org.json.JSONArray(declared))
            put("count", declared.size)
            put("detail", "那仓内清单声明过的那几件逐字 = " + declared.joinToString(",") +
                "（=> 而那**0 件**逐字**也是**一个**答**、=> 而那按 C-05 不猜填）")
        }
    }

    /**
     * 「跨 app 边界」那一族一个产品入口（是产品那一面的事，不是给程序那族开的后门）。
     * 缺陷 C-102：旧版在设备上没有可走的路——公开方法里没有 `app.launch` / `app.listInstalled` / `ui.tap`，而这族只能经桥调、
     *   桥要求会话有 `GROUP_CROSS_APP` 那一档，设备上没有任何一件声明过（按 C-05 报缺）。
     * 处置：产品入口经安装方那一路调（`INSTALLER_ID` 那族，是产品那一面）；它不是给程序的后门（程序仍要那一档），读数能取到。
     */
    fun crossApp(context: Context, method: String, params: JSONObject): JSONObject {
        val m = method.trim()
        if (m !in CROSS_APP_METHODS) {
            return failure(
                "cross-app", "cross-app",
                "那一族逐字不在那三格里：" + m +
                    "（=> 而那三格逐字是：" + CROSS_APP_METHODS.joinToString(",") +
                    "；=> 而那按 C-05 报缺）",
            )
        }
        //  一路经安装方那一族调，不是给程序的后门。
        val r = runCatching {
            lobos.api.CapabilityBroker.live()?.invokeLocal(
                lobos.api.CapabilityBroker.INSTALLER_ID,
                m,
                params ?: JSONObject(),
            )
        }.getOrNull()
        return r ?: failure("cross-app", "cross-app", "那宿主桥没起来（=> 而那按 C-05 报缺）")
    }

    /**
     * 堆那一格（「那一份在那一堆里待了多久、是不是还在」那一族）。
     * 缺陷 C-102：旧版只有上限、不答「待了多久」。后果（07 核出）：`Dalvik Heap` 85682 → 188222 → 331053 KB ≈ 323 MB →
     *   294482 KB ≈ 288 MB；分配量是 139 MB AllocSpace、两个 LOS 对象（69 MB 与 94 MB，一个大对象 55 到 94 MB），那就是「整读」
     *   的那一件（一份被留在堆里）。
     * 处置：读法名（`ReadRoute`）记着，记录带读法名与时刻；这一格答「待了多久、是不是还在」，判据不由「那一件是什么」顶掉。 */
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
                "=> 而那**还活着**的整读逐字 = " + live.length() + " 处、" +
                "=> 而那**读法名**逐字答的是「那一条路」、=> 而那**待了多久**逐字**也在**那记录里")
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
                    "（=> 而那九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "=> 而那按 C-05 报缺）",
            )
        }
    }

    /** 那 kmi 那一族（三选一：来源缺失 / 身份不符 / 解析失败）。 */
    private fun kmiCheck(context: Context, input: String): JSONObject {
        //  KMI 清单该从哪取：`abi.stg` 是内核的数据（`kmi.html` 与 `lkm.html` 只是网页、不是清单）
        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("kmi", false, "来源缺失", "那清单逐字不在：" + input + "（=> 而按 C-05 报缺）")
        }
        val text = runCatching { f.readText() }.getOrElse { e ->
            return hostBoundResult("kmi", false, "解析失败", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        //  清单的形状：`abi.stg` 每一行一个符号
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
        //  身份核：它是内核的数据
        val identity = runCatching {
            f.readLines().firstOrNull { it.trim().startsWith("#") }?.trim().orEmpty()
        }.getOrDefault("")
        val looksLikeKmi = syms.any { it.contains(".") } && syms.any { it.contains("_") }
        if (!looksLikeKmi) {
            return hostBoundResult(
                "kmi", false, "身份不符",
                "那清单逐字**不像**那 KMI 符号清单（=> 而那它逐字有 " + syms.size + " 行、" +
                    "=> 而那按 C-05 报缺）",
            )
        }
        return hostBoundResult(
            "kmi", true, "在位",
            "那清单逐字**能**解析出 " + syms.size + " 个符号（=> 而那身份逐字 = " +
                (if (identity.isBlank()) "（那首行逐字**没有**注释、=> 而那按 C-05 报缺）" else identity) + "）",
        )
    }

    /** 那 lkm 那一族（两选一：接口缺失或坏模块）。 */
    private fun lkmCheck(context: Context, input: String): JSONObject {
        //  模块是什么：`lkm.html` 只是网页、不是清单；模块该是 `.ko` 或模块清单
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
            return hostBoundResult("lkm", true, "在位", "那目录逐字有 " + mods.size + " 个模块（=> 而那头一个是 " + mods[0].name + "）")
        }
        //  坏模块：文件头不成形。
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
                "那模块逐字**不是** ELF（=> 而那 ELF 魔数逐字不是 7f 45 4c 46、=> 而按 C-05 报缺）",
            )
        }
        return hostBoundResult("lkm", true, "在位", "那模块逐字**是** ELF（=> 而那它逐字**能**加载）")
    }

    /** 那目标机型那一族（任一不一致）。 */
    private fun targetCheck(context: Context, input: String): JSONObject {
        //  目标机型是什么：声明那一档；实际那一档取 App 自己的 Build
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

    /** 那 UART 那一族（两选一：错节点或无输出）。 */
    private fun uartCheck(context: Context, input: String): JSONObject {
        //  UART 是什么：节点（宿主绑定那族），节点答「坏输入是什么」
        val node = input.trim()
        if (node.isEmpty()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字是空的（=> 而按 C-05 报缺）")
        }
        val f = java.io.File(node)
        if (!f.exists()) {
            return hostBoundResult(
                "uart", false, "错节点",
                "那节点逐字**不在**：" + node + "（=> 而那 UART 逐字是那宿主绑定的那几件、" +
                    "=> 而那按 C-05 报缺）",
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
        return hostBoundResult("uart", true, "在位", "那节点逐字有 " + text.length + " 字节（=> 而那头一句 = " +
            text.lineSequence().first().take(60) + "）")
    }

    /** 那 linker64 那一族（两选一：缺失或坏 ELF）。 */
    private fun linker64Check(context: Context, input: String): JSONObject {
        //  linker64 是什么：动态链接器
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
                "那文件逐字**不是** ELF（=> 而那 ELF 魔数逐字不是 7f 45 4c 46、=> 而按 C-05 报缺）",
            )
        }
        //  e_type 在偏移 16（e_ident 之后那两个字节）。
        val etype = (head[16].toInt() and 0xff) or ((head[17].toInt() and 0xff) shl 8)
        val kind = when (etype) {
            2 -> "ET_EXEC"
            3 -> "ET_DYN"
            else -> "e_type=" + etype + "（=> 而那它逐字既不是 2 也不是 3）"
        }
        if (etype != 2 && etype != 3) {
            return hostBoundResult(
                "linker64", false, "坏 ELF",
                "那 ELF 逐字的 e_type=" + etype + "（=> 而那它逐字既不是 ET_EXEC(2) 也不是 ET_DYN(3)、=> 而按 C-05 报缺）",
            )
        }
        return hostBoundResult("linker64", true, "在位", "那 linker64 逐字**是** ELF（=> 而那形态 = " + kind + "）")
    }

    /** 那文件那一族（三选一：越界 / 坏链接 / 不支持操作）。 */
    private fun fileCheck(context: Context, input: String): JSONObject {
        //  越界：路径逃出 LOB-OS 自己那一份；坏链接：软链指向一个不存在的目标；不支持操作：操作不在白名单里
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
        //  核越界。
        if (!canon.path.startsWith(root.path) && !canon.path.startsWith("/data/local/tmp")) {
            return hostBoundResult(
                "file", false, "越界",
                "那路径逐字**逃出**那 LOB-OS 自己那一份：" + canon.path + "（=> 而按 C-05 报缺）",
            )
        }
        //  核坏链接。
        if (target.isDirectory) {
            return hostBoundResult("file", false, "不支持操作", "那路径逐字是一个目录（=> 而按 C-05 报缺）")
        }
        if (!target.exists()) {
            return hostBoundResult(
                "file", false, "坏链接",
                "那路径逐字**不在**：" + canon.path + "（=> 而那按 C-05 报缺）",
            )
        }
        return hostBoundResult("file", true, "在位", "那路径逐字**在位**（= " + canon.path + "、" +
            (if (target.length() > 0) target.length().toString() + " 字节" else "空") + "）")
    }

    /** 那内存那一族（两选一：超限不回收或静默失败）。 */
    private fun memoryCheck(context: Context, input: String): JSONObject {
        //  限额是什么：声明那个数（单位 MB）；实际是什么：App 自己那可用内存
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
            "那声明限额=" + limitMb + "MB、=> 而那实际已用=" + usedMb + "MB / 上限=" + maxMb + "MB" +
                (if (over) "（=> 而那它逐字**超**了那限额、=> 而那按 C-05 报缺）" else "（=> 而那它逐字**没**超）"),
        )
    }

    /** 那安全那一族（越权动作被拒并留审计）。 */
    private fun securityCheck(context: Context, input: String): JSONObject {
        //  越权：路径逃出 LOB-OS 自己那一份；拒要留审计
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
            //  拒要留审计（C-122/C-123：要能换回原话）。
            lobos.services.log.RuntimeDiagnostics.append(
                context, "security", false, "越权动作被拒",
                "path=" + canon.path + "（=> 而那一族逐字要**留审计**）",
            )
            return hostBoundResult(
                "security", false, "越权动作被拒",
                "那路径逐字**越权**：" + canon.path + "（=> 而那审计逐字**已**留）",
            )
        }
        return hostBoundResult("security", true, "在位", "那路径逐字**在**那 LOB-OS 自己那一份里面（=> 而那它逐字**不**越权）")
    }

    /** 那时间那一族（两选一：时钟倒退或超时语义不符）。 */
    private fun timeCheck(context: Context, input: String): JSONObject {
        //  超时是什么：声明那个数（单位毫秒）；时钟倒退：墙上时钟比单调时钟那族慢
        val budgetMs = input.trim().toLongOrNull()
        if (budgetMs == null || budgetMs <= 0) {
            return hostBoundResult("time", false, "超时语义不符", "那预算逐字**不是**一个正数（=> 而按 C-05 报缺）")
        }
        val wall0 = System.currentTimeMillis()
        val mono0 = android.os.SystemClock.elapsedRealtime()
        val busy = runCatching { Thread.sleep(minOf(budgetMs, 2000L)) }.getOrNull()
        val wallDelta = System.currentTimeMillis() - wall0
        val monoDelta = android.os.SystemClock.elapsedRealtime() - mono0
        //  时钟倒退：墙上时钟那一趟倒了。
        val back = wallDelta < 0
        if (back) {
            return hostBoundResult(
                "time", false, "时钟倒退",
                "那墙上时钟逐字**倒**了：" + wallDelta + "ms（=> 而按 C-05 报缺）",
            )
        }
        //  超时语义：单调时钟那一趟不少于那预算。
        val overBudget = monoDelta > budgetMs
        return hostBoundResult(
            "time", !overBudget, if (overBudget) "超时语义不符" else "在位",
            "那预算=" + budgetMs + "ms、=> 而那墙上时钟过了 " + wallDelta + "ms、=> 而那单调时钟过了 " +
                monoDelta + "ms" + (if (overBudget) "（=> 而那它逐字**超**了那预算）" else "（=> 而那它逐字**没**超）"),
        )
    }

    /** 那九族的读数那一格（同形、不静默）。 */
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
            //  读法可查（`ReadRoute` 那一族记那一格）。
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
            //  APK 原件 body 的 realpath 相等。这一格只读，只作核对与诊断。
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

    /** 「跨 app 边界」那一族的三个方法（不给程序那族开后门）。 */
    private val CROSS_APP_METHODS = listOf("app.launch", "app.listInstalled", "ui.tap")

    private fun failure(method: String, requestId: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", -1)
        put("requestId", requestId)
        put("method", method)
        put("error", message)
    }
}
