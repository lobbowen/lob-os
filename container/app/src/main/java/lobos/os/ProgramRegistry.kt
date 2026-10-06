package lobos.os

import android.content.Context
import java.io.File

import org.json.JSONObject

enum class Restart { ON_FAILURE, ALWAYS, NEVER }

object ProgramRegistry {

    const val PROGRAMS_DIR = "programs"
    private const val DEFAULT_HEALTH = "/status"

    data class PortDecl(val port: Int, val env: String?, val health: String)

    data class Spec(
        val id: String,
        val version: String,
        val dir: File,
        val entry: String,
        val entryFile: File,
        val args: List<String>,
        val role: String,
        val http: PortDecl?,
        val capabilities: List<String>,
        val requires: List<String> = emptyList(),
        val env: Map<String, String>,
        val resident: Boolean,
        val restart: Restart,
        val maxRestarts: Int,
        val backoffMs: List<Long>,
        val invalid: String?,
        val note: String? = null,
    ) {
        val startable: Boolean get() = invalid == null
    }

    fun programRoot(ctx: Context): File = File(ctx.filesDir, PROGRAMS_DIR)

    fun listIds(ctx: Context): List<String> =
        programRoot(ctx).listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    private fun dirOf(ctx: Context, id: String): lobos.ProgramDir =
        lobos.ProgramDir(ctx, id, File(programRoot(ctx), id))

    fun installedVersions(ctx: Context, id: String): List<String> = dirOf(ctx, id).installedVersions()

    private fun manifest(ctx: Context, id: String, version: String): JSONObject? = runCatching {
        val f = File(dirOf(ctx, id).programDir(version), lobos.ProgramDir.MANIFEST_NAME)
        if (!f.isFile) null else JSONObject(f.readText())
    }.getOrNull()

    private fun currentVersion(ctx: Context, id: String): String? =
        runCatching { dirOf(ctx, id).currentVersion() }.getOrNull()

    fun spec(ctx: Context, id: String): Spec? {
        val version = currentVersion(ctx, id) ?: return null
        val dir = File(File(programRoot(ctx), id), version)
        val json = manifest(ctx, id, version)
        if (json == null) {
            return Spec(
                id = id, version = version, dir = dir,
                entry = "", entryFile = File(dir, ""), args = emptyList(),
                role = "app", http = null, capabilities = emptyList(), env = emptyMap(),
                resident = true, restart = Restart.ON_FAILURE, maxRestarts = 5,
                backoffMs = ProgramIndex.DEFAULT_BACKOFF,
                invalid = "清单缺失或不可解析（" + lobos.ProgramDir.MANIFEST_NAME + "）",
            )
        }
        val entry = json.optString("entry", "").trim()
        val requires = json.optJSONArray("requires")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val entryFile = File(dir, entry)
        val args = json.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val httpObj = json.optJSONObject(ManifestSchema.SC_HTTP)
        val http = httpObj?.let {
            PortDecl(
                port = it.optInt(ManifestSchema.HT_PORT, 0),
                env = it.optString("env", "").ifBlank { null },
                health = it.optString(ManifestSchema.HT_HEALTH, "").ifBlank { DEFAULT_HEALTH },
            )
        }
        val caps = json.optJSONArray("capabilities")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        val envObj = json.optJSONObject("env")
        val env: Map<String, String> = if (envObj == null) emptyMap() else buildMap {
            val names = envObj.names() ?: return@buildMap
            for (i in 0 until names.length()) {
                val k = names.optString(i)
                put(k, envObj.optString(k))
            }
        }
        val life = json.optJSONObject("lifecycle")
        val restartRaw = ManifestSchema.restartOf(life?.optString("restart", "on-failure") ?: "on-failure")
        val restart = when (restartRaw) {
            "on-failure", "on_failure" -> Restart.ON_FAILURE
            "always" -> Restart.ALWAYS
            "never" -> Restart.NEVER
            else -> Restart.ON_FAILURE
        }
        val backoff = life?.optJSONArray("backoff")?.let { a ->
            (0 until a.length()).map { a.optLong(it) }
        }?.filter { it > 0 }?.takeIf { it.isNotEmpty() }
            ?: ProgramIndex.DEFAULT_BACKOFF

        val schemaErrors = ManifestSchema.validate(json)
        val invalid = when {
            schemaErrors.isNotEmpty() -> schemaErrors.first()
            !entryFile.exists() -> "入口不存在：" + entryFile.absolutePath
            else -> null
        }
        val note = if (args == null) "清单未声明 args：按空参数启动（需要子命令的程序会打印用法并退出，请安装携带 args 的新包）" else null
        return Spec(
            id = id,
            version = version,
            dir = dir,
            entry = entry,
            entryFile = entryFile,
            args = args ?: emptyList(),
            role = json.optString("role", "app"),
            http = http,
            capabilities = caps,
            requires = requires,
            env = env,
            resident = life?.optBoolean("resident", true) ?: true,
            restart = restart,
            maxRestarts = life?.optInt("maxRestarts", 5) ?: 5,
            backoffMs = backoff,
            invalid = invalid,
            note = note,
        )
    }

    fun list(ctx: Context): List<Spec> = listIds(ctx).mapNotNull { spec(ctx, it) }

}
