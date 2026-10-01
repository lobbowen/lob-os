package lobos.runtime

import android.content.Context
import java.io.File

object NodeProvisioner {

    fun ensureServerScript(context: Context): File {
        val script = File(context.filesDir, "server.js")
        context.assets.open("node/server.js").use { input ->
            script.outputStream().use { out -> input.copyTo(out) }
        }
        return script
    }

    const val NPM_GLOBAL_DIR_NAME = ".npm-global"

    fun globalPrefix(home: File): File = File(home, NPM_GLOBAL_DIR_NAME)

    fun globalBin(home: File): File = File(globalPrefix(home), "bin")

    fun globalNodeModules(home: File): File = File(File(globalPrefix(home), "lib"), "node_modules")

    fun ensureNpmPrefixRc(context: Context): File? {
        val rc = File(context.filesDir, ".npmrc")
        if (rc.isFile) return rc
        return try {
            lobos.os.StateFiles.writeAtomic(rc, "prefix=" + globalPrefix(context.filesDir).absolutePath + "\n")
            rc
        } catch (_: Throwable) {
            null
        }
    }

    fun ensureAdbClientScripts(context: Context): File {
        val names = listOf(
            "cli.js", "index.js", "pairing.js", "transport.js",
            "spake2.js", "ed25519.js", "x509.js", "adbkey.js",
        )
        val dir = File(context.filesDir, "adb-client")
        dir.mkdirs()
        for (name in names) {
            ensureAssetCopied(context, "node/adb-client/$name", File(dir, name))
        }
        return dir
    }

    fun ensureKernelVerifyScript(context: Context): File {
        return ensureAssetCopied(context, "node/program-verify.js", File(context.filesDir, "program-verify.js"))
    }

    fun ensureOtaPublicKey(context: Context): File {
        return ensureAssetCopied(context, "ota-public.pem", File(context.filesDir, "ota-public.pem"))
    }

    fun ensureEnvShim(context: Context): File? {
        return try {
            ensureAssetCopied(context, "node/android-env-shim.cjs", File(context.filesDir, "android-env-shim.cjs"))
        } catch (e: Throwable) {
            android.util.Log.w("NodeProvisioner", "安卓语义垫片落地失败（不阻断启动）", e)
            null
        }
    }

    private fun ensureAssetCopied(context: Context, assetPath: String, dest: File): File {
        val assetBytes = context.assets.open(assetPath).use { it.readBytes() }
        if (dest.exists() && dest.length() == assetBytes.size.toLong()) {
            val same = try {
                dest.readBytes().contentEquals(assetBytes)
            } catch (_: Throwable) {
                false
            }
            if (same) return dest
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(assetBytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("无法把 $assetPath 落地到 ${dest.absolutePath}")
        }
        return dest
    }
}
