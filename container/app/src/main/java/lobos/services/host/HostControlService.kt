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

object HostControlService {
    const val MANAGEMENT_ID = "lobos.installer"
    const val MANAGEMENT_GENERATION = 1L

    fun pieces(context: Context): JSONArray {
        val app = context.applicationContext
        val origins = PieceOriginStore.all(app).associateBy { it.id }
        val arr = JSONArray()
        for (s in PieceUpdater.states(app)) {
            val o = origins[s.id]

            val status = PieceUpdater.rollbackStatus(app, s.id)
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("installedVersion", s.installedVersion ?: JSONObject.NULL)

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

    fun rollbackJudgement(context: Context, id: String): PieceUpdater.RollbackStatus {
        val app = context.applicationContext
        return PieceUpdater.rollbackStatus(app, id.trim())
    }

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
            return failure(method, "exec-direct", "那一件逐字不在：" + path + "（按 C-05 报缺）")
        }

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

    fun verifyDeviceNodes(context: Context): JSONObject {
        val method = "sysdev.verify"
        val root = lobos.kernel.sysdev.SysDev.root(context)
        if (!root.isDirectory) {
            return failure(method, "sysdev-verify", "那设备节点目录逐字不在：" + root.absolutePath + "（按 C-05 报缺）")
        }
        val bad = lobos.kernel.sysdev.SysDev.verifyNodes(root)

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

                val diff = if (bad.second == lobos.kernel.sysdev.SysDev.NodeFault.TOO_WIDE) {
                    lobos.kernel.sysdev.SysDev.modeDiffOf(bad.first)
                } else ""
                put("modeDiff", diff)
                put("detail", "节点不合声明：" + bad.first + " —— " + lobos.kernel.sysdev.SysDev.faultName(bad.second) +
                    (if (diff.isBlank()) "" else "（" + diff + "）"))
            }
        }
    }

    fun hostBoundPositive(context: Context, family: String, input: String): JSONObject {
        val method = "hostBound.positive"
        when (family.trim()) {
            "kmi" -> {
                val r = kmiCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 KMI 符号清单、它逐字**能**解析出那符号、它逐字**能**解析出**那一个数**个符号。")
            }
            "lkm" -> {
                val r = lkmCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那模块、它逐字**能**加载、目录逐字**有****那一个数**个模块。")
            }
            "target" -> {
                val r = targetCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那声明逐字与那实际逐字**一致**、三者（构建目标 / 运行目标 / 设备身份）逐字是**那一个值**。")
            }
            "uart" -> {
                val r = uartCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那节点逐字**在位**、它逐字**有**输出、输出逐字含**那一条**。")
            }
            "linker64" -> {
                val r = linker64Check(context, input)
                return r.put("side", "positive")
                    .put("expect", "那一件逐字**是**那 ELF、 e_type 逐字是 **2** 或 **3**、一个 ELF 逐字**在位**。")
            }
            "file" -> {
                val r = fileCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**在**那 LOB-OS 自己那一份里面、它逐字**在位**、一次落-读-删逐字**成**。")
            }
            "memory" -> {
                val r = memoryCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那已用逐字**没超**了那声明限额、限额逐字是**那一个数**。")
            }
            "security" -> {
                val r = securityCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那路径逐字**不**越权、一次越权动作逐字**被拒**、它逐字**留审计**。")
            }
            "time" -> {
                val r = timeCheck(context, input)
                return r.put("side", "positive")
                    .put("expect", "那墙上时钟逐字**没倒**、单调时钟那一趟逐字**没超**了那预算、预算逐字是**那一个数**。")
            }
            else -> return failure(
                method, "host-bound-positive",
                "那一族逐字不在那九族里：" + family +
                    "（九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "按 C-05 报缺）",
            )
        }
    }

    fun listDeclaredApps(context: Context): JSONObject {

        val declared = runCatching {
            lobos.api.CapabilityBroker.live()?.declaredPackages()
        }.getOrNull() ?: emptyList()
        return JSONObject().apply {
            put("ok", true)
            put("packages", org.json.JSONArray(declared))
            put("count", declared.size)
            put("detail", "那仓内清单声明过的那几件逐字 = " + declared.joinToString(",") +
                "（**0 件**逐字**也是**一个**答**、按 C-05 不猜填）")
        }
    }

    fun crossApp(context: Context, method: String, params: JSONObject): JSONObject {
        val m = method.trim()
        if (m !in CROSS_APP_METHODS) {
            return failure(
                "cross-app", "cross-app",
                "那一族逐字不在那三格里：" + m +
                    "（三格逐字是：" + CROSS_APP_METHODS.joinToString(",") +
                    "；按 C-05 报缺）",
            )
        }

        val r = runCatching {
            lobos.api.CapabilityBroker.live()?.invokeLocal(
                lobos.api.CapabilityBroker.INSTALLER_ID,
                m,
                params ?: JSONObject(),
            )
        }.getOrNull()
        return r ?: failure("cross-app", "cross-app", "那宿主桥没起来（按 C-05 报缺）")
    }

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
                "**还活着**的整读逐字 = " + live.length() + " 处、" +
                "**读法名**逐字答的是「那一条路」、**待了多久**逐字**也在**那记录里")
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
                    "（九族逐字是：kmi / lkm / target / uart / linker64 / file / memory / security / time；" +
                    "按 C-05 报缺）",
            )
        }
    }

    private fun kmiCheck(context: Context, input: String): JSONObject {

        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("kmi", false, "来源缺失", "那清单逐字不在：" + input + "（按 C-05 报缺）")
        }
        val text = runCatching { f.readText() }.getOrElse { e ->
            return hostBoundResult("kmi", false, "解析失败", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }

        val lines = text.lines()
        if (lines.isEmpty()) {
            return hostBoundResult("kmi", false, "解析失败", "那清单逐字是空的（按 C-05 报缺）")
        }
        val syms = lines.mapNotNull { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) null else t.substringBeforeLast(' ').trim()
        }
        if (syms.isEmpty()) {
            return hostBoundResult("kmi", false, "解析失败", "那清单逐字**一个**符号逐字都**没**解析出来")
        }

        val identity = runCatching {
            f.readLines().firstOrNull { it.trim().startsWith("#") }?.trim().orEmpty()
        }.getOrDefault("")
        val looksLikeKmi = syms.any { it.contains(".") } && syms.any { it.contains("_") }
        if (!looksLikeKmi) {
            return hostBoundResult(
                "kmi", false, "身份不符",
                "那清单逐字**不像**那 KMI 符号清单（它逐字有 " + syms.size + " 行、" +
                    "按 C-05 报缺）",
            )
        }
        return hostBoundResult(
            "kmi", true, "在位",
            "那清单逐字**能**解析出 " + syms.size + " 个符号（身份逐字 = " +
                (if (identity.isBlank()) "（那首行逐字**没有**注释、按 C-05 报缺）" else identity) + "）",
        )
    }

    private fun lkmCheck(context: Context, input: String): JSONObject {

        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("lkm", false, "接口缺失", "那模块逐字不在：" + input + "（按 C-05 报缺）")
        }
        if (f.isDirectory) {
            val mods = f.listFiles()?.filter { it.name.endsWith(".ko") }?.sortedBy { it.name }
                ?: emptyList()
            if (mods.isEmpty()) {
                return hostBoundResult("lkm", false, "接口缺失", "那目录逐字**没有** `.ko` 模块（按 C-05 报缺）")
            }
            return hostBoundResult("lkm", true, "在位", "那目录逐字有 " + mods.size + " 个模块（头一个是 " + mods[0].name + "）")
        }

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
                "那模块逐字**不是** ELF（ ELF 魔数逐字不是 7f 45 4c 46、按 C-05 报缺）",
            )
        }
        return hostBoundResult("lkm", true, "在位", "那模块逐字**是** ELF（它逐字**能**加载）")
    }

    private fun targetCheck(context: Context, input: String): JSONObject {

        val declared = input.trim()
        if (declared.isEmpty()) {
            return hostBoundResult("target", false, "答不出", "那声明逐字是空的（按 C-05 报缺）")
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
                    " abi=" + abi + "（按 C-05 报缺）",
            )
        }
        return hostBoundResult("target", true, "一致", "那声明逐字与那实际逐字**一致**（= " + declared + "）")
    }

    private fun uartCheck(context: Context, input: String): JSONObject {

        val node = input.trim()
        if (node.isEmpty()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字是空的（按 C-05 报缺）")
        }
        val f = java.io.File(node)
        if (!f.exists()) {
            return hostBoundResult(
                "uart", false, "错节点",
                "那节点逐字**不在**：" + node + "（ UART 逐字是那宿主绑定的那几件、" +
                    "按 C-05 报缺）",
            )
        }
        if (!f.canRead()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字**读**不到（按 C-05 报缺）")
        }
        val text = runCatching { f.readText() }.getOrElse { e ->
            return hostBoundResult("uart", false, "无输出", e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        if (text.isBlank()) {
            return hostBoundResult("uart", false, "无输出", "那节点逐字**空**的（按 C-05 报缺）")
        }
        return hostBoundResult("uart", true, "在位", "那节点逐字有 " + text.length + " 字节（头一句 = " +
            text.lineSequence().first().take(60) + "）")
    }

    private fun linker64Check(context: Context, input: String): JSONObject {

        val f = java.io.File(input)
        if (!f.exists()) {
            return hostBoundResult("linker64", false, "缺失", "那 linker64 逐字不在：" + input + "（按 C-05 报缺）")
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
                "那文件逐字**不是** ELF（ ELF 魔数逐字不是 7f 45 4c 46、按 C-05 报缺）",
            )
        }

        val etype = (head[16].toInt() and 0xff) or ((head[17].toInt() and 0xff) shl 8)
        val kind = when (etype) {
            2 -> "ET_EXEC"
            3 -> "ET_DYN"
            else -> "e_type=" + etype + "（它逐字既不是 2 也不是 3）"
        }
        if (etype != 2 && etype != 3) {
            return hostBoundResult(
                "linker64", false, "坏 ELF",
                "那 ELF 逐字的 e_type=" + etype + "（它逐字既不是 ET_EXEC(2) 也不是 ET_DYN(3)、按 C-05 报缺）",
            )
        }
        return hostBoundResult("linker64", true, "在位", "那 linker64 逐字**是** ELF（形态 = " + kind + "）")
    }

    private fun fileCheck(context: Context, input: String): JSONObject {

        val path = input.trim()
        if (path.isEmpty()) {
            return hostBoundResult("file", false, "越界", "那路径逐字是空的（按 C-05 报缺）")
        }
        val target = java.io.File(path)
        val root = context.filesDir.canonicalFile
        val canon = runCatching { target.canonicalFile }.getOrNull()
        if (canon == null) {
            return hostBoundResult("file", false, "坏链接", "那路径逐字**解不出**那真的一个（按 C-05 报缺）")
        }

        if (!canon.path.startsWith(root.path) && !canon.path.startsWith("/data/local/tmp")) {
            return hostBoundResult(
                "file", false, "越界",
                "那路径逐字**逃出**那 LOB-OS 自己那一份：" + canon.path + "（按 C-05 报缺）",
            )
        }

        if (target.isDirectory) {
            return hostBoundResult("file", false, "不支持操作", "那路径逐字是一个目录（按 C-05 报缺）")
        }
        if (!target.exists()) {
            return hostBoundResult(
                "file", false, "坏链接",
                "那路径逐字**不在**：" + canon.path + "（按 C-05 报缺）",
            )
        }
        return hostBoundResult("file", true, "在位", "那路径逐字**在位**（= " + canon.path + "、" +
            (if (target.length() > 0) target.length().toString() + " 字节" else "空") + "）")
    }

    private fun memoryCheck(context: Context, input: String): JSONObject {

        val limitMb = input.trim().toLongOrNull()
        if (limitMb == null || limitMb <= 0) {
            return hostBoundResult("memory", false, "静默失败", "那限额逐字**不是**一个正数（按 C-05 报缺）")
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
            "那声明限额=" + limitMb + "MB、实际已用=" + usedMb + "MB / 上限=" + maxMb + "MB" +
                (if (over) "（它逐字**超**了那限额、按 C-05 报缺）" else "（它逐字**没**超）"),
        )
    }

    private fun securityCheck(context: Context, input: String): JSONObject {

        val path = input.trim()
        if (path.isEmpty()) {
            return hostBoundResult("security", false, "静默失败", "那路径逐字是空的（按 C-05 报缺）")
        }
        val root = context.filesDir.canonicalFile
        val canon = runCatching { java.io.File(path).canonicalFile }.getOrNull()
        if (canon == null) {
            return hostBoundResult("security", false, "静默失败", "那路径逐字**解不出**那真的一个（按 C-05 报缺）")
        }
        val denied = !canon.path.startsWith(root.path)
        if (denied) {

            lobos.services.log.RuntimeDiagnostics.append(
                context, "security", false, "越权动作被拒",
                "path=" + canon.path + "（一族逐字要**留审计**）",
            )
            return hostBoundResult(
                "security", false, "越权动作被拒",
                "那路径逐字**越权**：" + canon.path + "（审计逐字**已**留）",
            )
        }
        return hostBoundResult("security", true, "在位", "那路径逐字**在**那 LOB-OS 自己那一份里面（它逐字**不**越权）")
    }

    private fun timeCheck(context: Context, input: String): JSONObject {

        val budgetMs = input.trim().toLongOrNull()
        if (budgetMs == null || budgetMs <= 0) {
            return hostBoundResult("time", false, "超时语义不符", "那预算逐字**不是**一个正数（按 C-05 报缺）")
        }
        val wall0 = System.currentTimeMillis()
        val mono0 = android.os.SystemClock.elapsedRealtime()
        val busy = runCatching { Thread.sleep(minOf(budgetMs, 2000L)) }.getOrNull()
        val wallDelta = System.currentTimeMillis() - wall0
        val monoDelta = android.os.SystemClock.elapsedRealtime() - mono0

        val back = wallDelta < 0
        if (back) {
            return hostBoundResult(
                "time", false, "时钟倒退",
                "那墙上时钟逐字**倒**了：" + wallDelta + "ms（按 C-05 报缺）",
            )
        }

        val overBudget = monoDelta > budgetMs
        return hostBoundResult(
            "time", !overBudget, if (overBudget) "超时语义不符" else "在位",
            "那预算=" + budgetMs + "ms、墙上时钟过了 " + wallDelta + "ms、单调时钟过了 " +
                monoDelta + "ms" + (if (overBudget) "（它逐字**超**了那预算）" else "（它逐字**没**超）"),
        )
    }

    private fun hostBoundResult(family: String, ok: Boolean, cls: String, detail: String): JSONObject =
        JSONObject().apply {
            put("ok", ok)
            put("family", family)
            put("class", cls)
            put("detail", detail)
        }

    fun loadPeSample(context: Context, asset: String): JSONObject {
        val app = context.applicationContext

        val method = "lobos.compat.pe.sample"
        val sample = peSamples.firstOrNull { it.asset == asset }
            ?: return failure(method, "pe-sample", "没有这一份 PE L2 样本：" + asset)
        return try {
            val out = File(app.cacheDir, asset)
            out.parentFile?.mkdirs()
            app.assets.open(asset).use { input -> out.outputStream().use { output -> input.copyTo(output) } }

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

    fun rollback(context: Context, id: String, base: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext

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

    private val CROSS_APP_METHODS = listOf("app.launch", "app.listInstalled", "ui.tap")

    private fun failure(method: String, requestId: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", -1)
        put("requestId", requestId)
        put("method", method)
        put("error", message)
    }
}
