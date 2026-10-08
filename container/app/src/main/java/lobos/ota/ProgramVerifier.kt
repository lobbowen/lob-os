package lobos.ota

import android.content.Context
import android.system.Os
import java.io.File
import lobos.BuildConfig
import lobos.os.RuntimeEnvironment
import lobos.runtime.ProcessSupervisor
import org.json.JSONObject

object ProgramVerifier {

    data class VerifyOutcome(
        val ok: Boolean,
        val version: String?,
        val reason: String?,
        val detail: String,
        val raw: String,
        val entryOk: Boolean?,
    )

    fun verify(
        context: Context,
        zip: File,
        manifest: JSONObject?,
        nodeBin: File? = lobos.runtime.InstalledRuntime.binOf(context, InstalledRuntime.programRuntime(context).id),
        manifestFile: File? = null,
    ): VerifyOutcome {
        if (nodeBin == null) {
            return VerifyOutcome(
                false, null, "runtime-missing",
                lobos.runtime.InstalledRuntime.notInstalledHint(context, InstalledRuntime.programRuntime(context).id), "", null,
            )
        }
        val script = try {
        } catch (e: Throwable) {
            return VerifyOutcome(
                false, null, "verifier-script-missing",
                "内置校验脚本不可用: ${e.message}", "", null
            )
        }

        val pubKeyPath = try {
            lobos.os.AssetInstaller.install(context, "supply/component-public.pem", File(lobos.os.SystemDirs.etc(context), "component-public.pem")).absolutePath
        } catch (e: Throwable) {
            return VerifyOutcome(
                false, null, "public-key-missing",
                "公钥锚点不可用: ${e.message}", "", null
            )
        }

        val args = mutableListOf(
            nodeBin.absolutePath,
            script.absolutePath,
            "--zip", zip.absolutePath,
            "--pubkey", pubKeyPath,
        )
        args += listOf("--shell-protocol", BuildConfig.BRIDGE_PROTOCOL.toString())
        manifestFile?.takeIf { it.isFile }?.let { args += listOf("--manifest", it.absolutePath) }
        manifest?.optString("sha256", "")?.ifBlank { null }?.let { args += listOf("--sha256", it) }
        manifest?.optString("version", "")?.ifBlank { null }?.let { args += listOf("--version", it) }

        return try {
            val proc = ProcessSupervisor.spawn(
                command = args,
                cwd = context.filesDir,
                env = RuntimeEnvironment.treeRootEnv(
                    RuntimeEnvironment.treeRootFor(context), Os.getenv("PATH"),
                ),
                envMode = ProcessSupervisor.ENV_MERGE,
                redirectErrorStream = true,
                owner = ProcessSupervisor.OWNER_VERIFIER,
            ).process
            val out = StringBuilder()
            val pump = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (out.length < MAX_OUTPUT) out.append(line).append('\n')
                }
            }
            pump.start()
            val finished = proc.waitFor(VERIFY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return VerifyOutcome(false, null, "verifier-timeout",
                    "校验进程超时 ${VERIFY_TIMEOUT_MS}ms", out.toString(), null)
            }
            pump.join(1000)
            val raw = out.toString()

            val line = raw.lineSequence().lastOrNull { it.startsWith(RESULT_PREFIX) }
                ?: return VerifyOutcome(false, null, "verifier-no-result",
                    "校验进程未输出结果行（exit=${proc.exitValue()}）", raw, null)

            val json = try {
                JSONObject(line.substring(RESULT_PREFIX.length).trim())
            } catch (e: Throwable) {
                return VerifyOutcome(false, null, "verifier-bad-json",
                    "结果行不是合法 JSON: ${line.take(200)}", raw, null)
            }

            VerifyOutcome(
                ok = json.optBoolean("ok", false),
                version = json.optString("version", "").ifBlank { null },
                reason = json.optString("reason", "").ifBlank { null },
                detail = json.optString("detail", ""),
                raw = raw,
                entryOk = if (json.has("entryOk")) json.optBoolean("entryOk") else null,
            )
        } catch (e: Throwable) {
            VerifyOutcome(false, null, "verifier-spawn-failed",
                "${e::class.java.simpleName}: ${e.message ?: ""}", "", null)
        }
    }

    private const val RESULT_PREFIX = "LOBOS_VERIFY_RESULT "
    private const val VERIFY_TIMEOUT_MS = 60_000L
    private const val MAX_OUTPUT = 32 * 1024
}
