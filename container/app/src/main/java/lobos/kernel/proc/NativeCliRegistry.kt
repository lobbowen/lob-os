package lobos.kernel.proc

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Generic registry for native (non-bionic) CLI executables shipped as
 * nativeLibraryDir shared objects. This is a kernel mechanism: it knows
 * nothing about product names — each entry is declared by a manifest that
 * any component may drop into usr/lib/native-cli.json.
 *
 * Manifest shape:
 * {
 *   "commands": {
 *     "<command-name>": {
 *       "binary": "<nativeLib .so filename>",
 *       "loader": "<nativeLib musl loader .so filename, optional>",
 *       "libraryPath": "<dir, optional; defaults to nativeLibraryDir>",
 *       "env": { "NAME": "VALUE", ... }
 *     }
 *   }
 * }
 *
 * A command with a "loader" is launched through that loader (musl ABI).
 * A command without one is executed directly (static PIE).
 */
object NativeCliRegistry {

    const val MANIFEST_REL = "lib/native-cli.json"
    const val ASSET_MANIFEST = "sysconfig/native-cli.json"

    /** Ensure the system manifest is installed from assets (once, or on change). */
    fun ensureManifest(ctx: Context) {
        val dest = manifest(ctx)
        val asset = runCatching { ctx.assets.open(ASSET_MANIFEST).use { it.readBytes() } }.getOrNull() ?: return
        val current = if (dest.isFile) runCatching { dest.readBytes() }.getOrNull() else null
        if (current == null || !current.contentEquals(asset)) {
            runCatching {
                dest.parentFile?.mkdirs()
                dest.writeBytes(asset)
            }
        }
    }

    data class Entry(
        val name: String,
        val binary: File,
        val loader: File?,
        val libraryPath: File,
        val env: Map<String, String>,
    )

    fun manifest(ctx: Context): File = File(ctx.filesDir, MANIFEST_REL)

    /** Read all declared commands. Explicit manifest wins; otherwise discover
     *  nativeLib binaries by the generic `lib<name>-musl.so` convention. */
    fun entries(ctx: Context): List<Entry> {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val filesDir = ctx.filesDir
        // Authoritative manifest ships in assets; usr/lib copy (if any) may override.
        val manifestText = runCatching {
            ctx.assets.open(ASSET_MANIFEST).use { it.readBytes() }.toString(Charsets.UTF_8)
        }.getOrNull()
            ?: runCatching { manifest(ctx).readText() }.getOrNull()
            ?: "{}"
        val out = mutableListOf<Entry>()
        runCatching { JSONObject(manifestText) }.getOrNull()?.let { root ->
            val cmds = runCatching { root.getJSONObject("commands") }.getOrNull()
            if (cmds != null) for (name in cmds.keys()) {
                val obj = runCatching { cmds.getJSONObject(name) }.getOrNull() ?: continue
                val binName = obj.optString("binary", "").trim()
                if (binName.isEmpty()) continue
                val binary = File(nativeDir, binName)
                if (!binary.isFile) continue
                val loaderName = obj.optString("loader", "").trim()
                val loader = if (loaderName.isEmpty()) null else File(nativeDir, loaderName).takeIf { it.isFile }
                val libPathName = obj.optString("libraryPath", "").trim()
                val libraryPath = when {
                    libPathName.isEmpty() || libPathName == "@nativeLib" -> nativeDir
                    else -> File(libPathName)
                }
                fun expand(raw: String) = when {
                    raw == "@tmp" -> File(filesDir, ".tmp").absolutePath
                    raw == "@files" -> filesDir.absolutePath
                    raw.startsWith("@files/") -> File(filesDir, raw.removePrefix("@files/")).absolutePath
                    raw == "@nativeLib" -> nativeDir.absolutePath
                    else -> raw
                }
                val env = mutableMapOf<String, String>()
                val envObj = runCatching { obj.getJSONObject("env") }.getOrNull()
                if (envObj != null) for (k in envObj.keys()) env[k] = expand(envObj.optString(k, ""))
                out += Entry(name, binary, loader, libraryPath, env)
            }
        }
        // Generic discovery: any lib<name>-musl.so is a musl CLI, launched via
        // libmusl-ld.so with MUSL_ON_TERMUX. No product name is hard-coded here.
        val loader = File(nativeDir, "libmusl-ld.so").takeIf { it.isFile }
        if (loader != null) {
            val declared = out.map { it.binary.name }.toSet()
            nativeDir.listFiles()?.forEach { f ->
                val n = f.name
                if (!n.startsWith("lib") || !n.endsWith("-musl.so")) return@forEach
                if (declared.contains(n)) return@forEach
                val name = n.removePrefix("lib").removeSuffix("-musl.so")
                if (name.isEmpty()) return@forEach
                out += Entry(name, f, loader, nativeDir, mapOf("MUSL_ON_TERMUX" to "1"))
            }
        }
        return out
    }

    /** Generate a POSIX shell function that launches one entry with a real TTY. */
    fun shellFunction(e: Entry): String {
        val envPrefix = buildString {
            append("unset LD_PRELOAD; ")
            for ((k, v) in e.env) append("export ${k}=${escape(v)}; ")
        }
        val cmd = if (e.loader != null) {
            "exec ${escape(e.loader.absolutePath)} --library-path ${escape(e.libraryPath.absolutePath)} ${escape(e.binary.absolutePath)}"
        } else {
            "exec ${escape(e.binary.absolutePath)}"
        }
        return "${e.name}() { ($envPrefix $cmd \"\$@\"); }"
    }

    private fun escape(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
