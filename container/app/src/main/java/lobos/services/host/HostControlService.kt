package lobos.services.host

import android.content.Context
import java.util.UUID
import lobos.kernel.compat.PeLoader
import lobos.control.ControlClient
import lobos.kernel.proc.SessionRegistry
import lobos.services.log.RuntimeDiagnostics
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

    /** List the pieces that can be rolled back to their APK baseline. */
    fun pieces(context: Context): JSONArray {
        val arr = JSONArray()
        for (s in PieceUpdater.states(context.applicationContext)) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("installedVersion", s.installedVersion ?: JSONObject.NULL)
                put("updated", s.updated)
            })
        }
        return arr
    }

    fun update(context: Context, manifestUrl: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext
        val correlatedId = requestId.trim().ifEmpty { "update-" + UUID.randomUUID() }
        val url = manifestUrl.trim()
        if (url.isBlank()) return failure(correlatedId, "manifestUrl 为空")
        val broker = lobos.api.CapabilityBroker.live()
            ?: return failure(correlatedId, "宿主桥未启动：控制面此刻不可用")
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
        } catch (e: Throwable) { failure(correlatedId, e.message ?: e.javaClass.simpleName) }
            .also { response -> RuntimeDiagnostics.append(app, "host-control", response.optBoolean("ok", false), "宿主正式管理服务执行更新", "requestId=" + correlatedId + " manifestUrl=" + url + " dryRun=" + dryRun + " response=" + response) }
    }

    /** Load the shipped PE L2 sample through the production parser without executing it. */
    fun loadPeSample(context: Context): JSONObject {
        val app = context.applicationContext
        return try {
            val asset = "win32/plink.exe"
            val expectedSha256 = "7308e9d356f14aa1f08cab1af363f71a077bf35dcb49e50b363418044758cd1e"
            val sample = File(app.cacheDir, "win32/plink.exe")
            sample.parentFile?.mkdirs()
            app.assets.open(asset).use { input -> sample.outputStream().use { output -> input.copyTo(output) } }
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(sample.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (actual != expectedSha256) return failure("pe-sample", "PE L2 样本 sha256 不匹配")
            val image = PeLoader.parse(sample.readBytes())
            JSONObject().apply {
                put("ok", true); put("requestId", "pe-sample"); put("asset", asset)
                put("bytes", sample.length()); put("sha256", actual)
                put("machine", image.machine); put("pe32Plus", image.pe32Plus)
                put("entryPoint", image.entryPointRva); put("imageBase", image.imageBase)
                put("sections", image.sections.size); put("imports", image.imports.size)
            }
        } catch (e: Throwable) { failure("pe-sample", e.message ?: e.javaClass.simpleName) }
    }

    /** Formal rollback of one piece to its APK baseline. */
    fun rollback(context: Context, id: String, base: String, requestId: String, dryRun: Boolean): JSONObject {
        val app = context.applicationContext
        val pieceId = id.trim()
        val selectedBase = base.trim().ifEmpty { "apk" }
        val correlatedId = requestId.trim().ifEmpty { "rollback-" + UUID.randomUUID() }
        if (pieceId.isBlank()) return failure(correlatedId, "id 为空")
        if (selectedBase != "apk") return failure(correlatedId, "base 只接受 apk")
        val broker = lobos.api.CapabilityBroker.live()
            ?: return failure(correlatedId, "宿主桥未启动：控制面此刻不可用")
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
            failure(correlatedId, e.message ?: e.javaClass.simpleName)
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

    private fun failure(requestId: String, message: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("code", -1)
        put("requestId", requestId)
        put("method", "lobos.sys.native.rollback")
        put("error", message)
    }
}
