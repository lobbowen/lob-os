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
            val bytes = out.readBytes()
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

    private fun failure(method: String, requestId: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", -1)
        put("requestId", requestId)
        put("method", method)
        put("error", message)
    }
}
