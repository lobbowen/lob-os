package lobos

import android.content.Context
import android.os.Build
import java.io.File
import lobos.capability.BridgeTokens
import lobos.capability.CapStatus
import lobos.capability.CapabilityCatalog
import lobos.capability.CapabilityEvidenceCollector
import lobos.capability.Evidence
import lobos.os.ProgramDir
import lobos.permissions.LifecycleChecks
import org.json.JSONObject

object ProvisioningProbe {

    const val LIFECYCLE = "lifecycle"

    const val SNAPSHOT = "provisioning.json"

    fun snapshotFile(ctx: Context): File = File(ctx.filesDir, SNAPSHOT)

    fun snapshot(ctx: Context): JSONObject? = runCatching {
        val f = snapshotFile(ctx)
        if (f.isFile) JSONObject(f.readText()) else null
    }.getOrNull()

    fun run(ctx: Context): Pair<Int, Int> = run(ctx, CapabilityEvidenceCollector.systemReads(ctx))

    internal fun run(ctx: Context, e: Evidence): Pair<Int, Int> {
        val verdicts = CapabilityCatalog.evaluate(e)
        val results = CapabilityCatalog.ALL.map { c ->
            val v = verdicts.getValue(c.id)
            ProbeResult(
                id = c.id,
                label = c.title,
                segment = c.segment,
                optional = c.optional,
                ok = v.status == CapStatus.GRANTED,
                status = v.status.name + "：" + v.detail,
                hint = c.acquirer(e).joinToString(" → ") { it.label }
                    .ifBlank { "无需动作" },
            )
        } + checkLifecycle(ctx)

        for (r in results) {
            RuntimeDiagnostics.append(ctx, "probe:${r.id}", r.ok, "${r.label} —— ${r.status}", r.hint)
        }
        val gating = results.filterNot { it.optional }
        val passed = gating.count { it.ok }
        RuntimeDiagnostics.append(
            ctx,
            "probe",
            passed == gating.size,
            "预置体检：$passed/${gating.size} 项通过",
            if (passed == gating.size) "全部控制面能力就绪"
            else "缺失项对应的 bridge 方法组会返回 -32001（这是预期降级，不是崩溃）；" +
                "可选能力（屏幕捕获等）不计入分母",
        )
        writeSnapshot(ctx, e, results)
        return passed to gating.size
    }

    private fun checkLifecycle(ctx: Context): ProbeResult {
        val lines = LifecycleChecks.collect(ctx)
        val ok = lines.count { it.ok }
        return ProbeResult(
            id = LIFECYCLE,
            label = "生命周期风险",
            segment = "",
            optional = false,
            ok = ok == lines.size,
            status = "$ok/${lines.size} 项就绪",
            hint = lines.joinToString("\n") { it.title + "=" + it.detail },
        )
    }

    fun refreshProgramOtaVersions(ctx: Context) {
        try {
            val f = snapshotFile(ctx)
            if (!f.isFile) return
            val ids = lobos.os.ProgramRegistry.listIds(ctx)
            val single = ids.singleOrNull()?.let { ProgramDir(ctx, it) }
            val obj = org.json.JSONObject(f.readText())
            obj.put("programVersion", single?.currentVersion() ?: "")
            obj.put("programFloor", single?.floorVersion() ?: "")
            obj.put("programPending", single?.pending()?.version ?: "")
            obj.put("programs", org.json.JSONObject(ids.associateWith { ProgramDir(ctx, it).currentVersion() ?: "" }))
            obj.put("checkedAt", System.currentTimeMillis())
            lobos.os.StateFiles.writeAtomic(f, obj.toString(2))
        } catch (_: Throwable) {
        }
    }

    private fun writeSnapshot(ctx: Context, e: Evidence, results: List<ProbeResult>) {
        try {
            val ids = lobos.os.ProgramRegistry.listIds(ctx)
            val single = ids.singleOrNull()?.let { ProgramDir(ctx, it) }
            val obj = org.json.JSONObject().apply {
                put("schema", 2)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("appVersionCode", BuildConfig.VERSION_CODE)
                put("bridgeProtocol", BuildConfig.BRIDGE_PROTOCOL)
                put("programVersion", single?.currentVersion() ?: "")
                put("programFloor", single?.floorVersion() ?: "")
                put("programPending", single?.pending()?.version ?: "")
                put("programs", org.json.JSONObject(ids.associateWith { ProgramDir(ctx, it).currentVersion() ?: "" }))
                put("checkedAt", System.currentTimeMillis())
                put("androidApi", Build.VERSION.SDK_INT)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("capabilities", org.json.JSONArray(BridgeTokens.from(e).toList()))
                put("checks", org.json.JSONArray().apply {
                    for (r in results) {
                        put(org.json.JSONObject().apply {
                            put("id", r.id)
                            put("label", r.label)
                            put("segment", r.segment)
                            put("optional", r.optional)
                            put("ok", r.ok)
                            put("status", r.status)
                            put("hint", r.hint)
                        })
                    }
                })
            }
            lobos.os.StateFiles.writeAtomic(snapshotFile(ctx), obj.toString(2))
        } catch (_: Throwable) {
        }
    }

    data class ProbeResult(
        val id: String,
        val label: String,
        val segment: String,
        val optional: Boolean,
        val ok: Boolean,
        val status: String,
        val hint: String,
    )
}
