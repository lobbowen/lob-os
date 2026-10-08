package lobos.runtime

import android.content.Context
import android.util.Log
import java.io.File

object NodeProvisioner {

    fun ensureServerScript(context: Context): File =
        lobos.os.AssetInstaller.install(context, "node/server.js", File(context.filesDir, "server.js"))

    private const val NPM_GLOBAL_DIR_NAME = ".npm-global"

    fun globalPrefix(home: File): File = File(home, NPM_GLOBAL_DIR_NAME)

    fun globalBin(home: File): File = File(globalPrefix(home), "bin")

    fun globalNodeModules(home: File): File = File(File(globalPrefix(home), "lib"), "node_modules")

    fun ensureNpmPrefixRc(context: Context): File? {
        val rc = File(context.filesDir, ".npmrc")
        if (rc.isFile) return rc
        return try {
            lobos.os.StateFiles.writeAtomic(
                rc, "prefix=" + globalPrefix(context.filesDir).absolutePath + "\n"
            )
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
            lobos.os.AssetInstaller.install(context, "node/adb-client/$name", File(dir, name))
        }
        return dir
    }

    fun ensureKernelVerifyScript(context: Context): File =
        lobos.os.AssetInstaller.install(
            context, "node/program-verify.js", File(context.filesDir, "program-verify.js")
        )

    fun ensureOtaPublicKey(context: Context): File =
        lobos.os.AssetInstaller.install(
            context, "supply/component-public.pem", File(context.filesDir, "supply/component-public.pem")
        )

    fun ensureEnvShim(context: Context): File? =
        lobos.os.AssetInstaller.installOrNull(
            context, "node/android-env-shim.cjs", File(context.filesDir, "android-env-shim.cjs")
        ).also { if (it == null) Log.w("NodeProvisioner", "安卓语义垫片落地失败（不阻断启动）") }
}