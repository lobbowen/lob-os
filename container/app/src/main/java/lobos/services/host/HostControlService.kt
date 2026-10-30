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

    /** List the pieces with their entry origin (APK original or OTA version). */
    fun pieces(context: Context): JSONArray {
        val app = context.applicationContext
        val origins = PieceOriginStore.all(app).associateBy { it.id }
        val arr = JSONArray()
        for (s in PieceUpdater.states(app)) {
            val o = origins[s.id]
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("installedVersion", s.installedVersion ?: JSONObject.NULL)
                put("updated", s.updated)
                put("origin", o?.active ?: "")
                put("apkVersion", o?.apkVersion ?: "")
                put("apkTarget", o?.apkTargetPath ?: "")
                put("otaVersion", o?.otaVersion ?: "")
                put("otaTarget", o?.otaTargetPath ?: "")
                put("hasApkOriginal", o?.hasApkOriginal == true)
                put("hasOta", o?.hasOta == true)
            })
        }
        return arr
    }

    fun update(context: Context, manifestUrl: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext
        val method = "lobos.sys.native.update"
        val correlatedId = requestId.trim().ifEmpty { "update-" + UUID.randomUUID() }
        val url = manifestUrl.trim()
        if (url.isBlank()) return failure("lobos.sys.native.update", correlatedId, "manifestUrl 为空")
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
                put("method", "lobos.sys.native.update"); put("result", result.value)
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
        val sample = peSamples.firstOrNull { it.asset == asset }
            ?: return failure("lobos.compat.pe.sample", "pe-sample", "没有这一份 PE L2 样本：" + asset)
        return try {
            val out = File(app.cacheDir, asset)
            out.parentFile?.mkdirs()
            app.assets.open(asset).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            val bytes = out.readBytes()
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (actual != sample.sha256) {
                return failure("lobos.compat.pe.sample", "pe-sample", "PE L2 样本 sha256 不匹配：" + asset)
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
        } catch (e: Throwable) { failure("lobos.compat.pe.sample", "pe-sample", e.message ?: e.javaClass.simpleName) }
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
        val method = "lobos.sys.native.rollback"
        val pieceId = id.trim()
        val selectedBase = base.trim().ifEmpty { "apk" }
        val correlatedId = requestId.trim().ifEmpty { "rollback-" + UUID.randomUUID() }
        if (pieceId.isBlank()) return failure("lobos.sys.native.rollback", correlatedId, "id 为空")
        if (selectedBase != "apk") return failure("lobos.sys.native.rollback", correlatedId, "base 只接受 apk")
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
            JSONObject().apply {
                put("ok", result.ok)
                put("code", result.code)
                put("requestId", correlatedId)
                put("method", "lobos.sys.native.rollback")
                put("result", result.value)
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
