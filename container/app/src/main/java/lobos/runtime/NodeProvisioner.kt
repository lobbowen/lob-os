package lobos.runtime

import android.content.Context
import android.util.Log
import java.io.File

object NodeProvisioner {

    fun ensureServerScript(context: Context): File =
        lobos.os.AssetInstaller.install(context, "node/server.js", File(lobos.os.SystemDirs.etc(context), "server.js"))

    /**
     * npm 全局前缀 —— **不写死目录名**。
     *
     * 此前是 `用户目录下的隐藏目录` 一个常量，npm 装到哪它不管、自己换前缀也不管。
     * 现在按「登记的位置就是落位的位置」：前缀落在 `usr/lib/npm-global`，
     * 那件东西本身就是系统的一部分（与 `/usr` 平级），不再是某个用户目录里的隐藏目录。
     *
     * `bin` 与 `lib/node_modules` 是 npm 自己的布局约定（`prefix/bin`、`prefix/lib/node_modules`）
     * —— 那是 npm 读 `.npmrc` 后自己会去找的位置，内核只是把 prefix 指对。
     */
    fun globalPrefix(ctx: Context): File = File(lobos.os.SystemDirs.usr(ctx), "npm-global")

    fun globalBin(ctx: Context): File = File(globalPrefix(ctx), "bin")

    fun globalNodeModules(ctx: Context): File = File(File(globalPrefix(ctx), "lib"), "node_modules")

    fun ensureNpmPrefixRc(context: Context): File? {
        val rc = File(lobos.os.SystemDirs.etc(context), "npmrc")
        if (rc.isFile) return rc
        return try {
            rc.parentFile?.mkdirs()
            lobos.os.StateFiles.writeAtomic(
                rc, "prefix=" + globalPrefix(context).absolutePath + "\n"
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
        val dir = File(lobos.os.SystemDirs.etc(context), "adb-client")
        dir.mkdirs()
        for (name in names) {
            lobos.os.AssetInstaller.install(context, "node/adb-client/$name", File(dir, name))
        }
        return dir
    }

    fun ensureKernelVerifyScript(context: Context): File =
        lobos.os.AssetInstaller.install(
            context, "node/program-verify.js", File(lobos.os.SystemDirs.etc(context), "program-verify.js")
        )

    fun ensureOtaPublicKey(context: Context): File =
        lobos.os.AssetInstaller.install(
            context, "supply/component-public.pem", File(lobos.os.SystemDirs.etc(context), "component-public.pem")
        )

    fun ensureEnvShim(context: Context): File? =
        lobos.os.AssetInstaller.installOrNull(
            context, "node/android-env-shim.cjs", File(lobos.os.SystemDirs.etc(context), "env-shim.cjs")
        ).also { if (it == null) Log.w("NodeProvisioner", "安卓语义垫片落地失败（不阻断启动）") }
}