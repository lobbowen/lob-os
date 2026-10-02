package lobos.runtime

import android.content.Context
import lobos.RuntimeDiagnostics
import android.system.Os
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

object SupplyProvisioner {

    private const val CHANNEL_ASSET = "supply/channel.json"
    private const val PUBKEY_ASSET = "supply/userland-public.pem"
    private const val MANIFEST_NAME = "userland-manifest.json"
    private const val FETCH_TIMEOUT_MS = 30000

    fun toolchainDir(ctx: Context): File = File(PrefixProvisioner.libDir(ctx), "toolchain")
    fun entryLink(ctx: Context, name: String): File = File(PrefixProvisioner.binDir(ctx), name)

    internal fun manifestDir(ctx: Context): String? {
        return try {
            val t = ctx.assets.open(CHANNEL_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(t)
            val base = o.optString("baseUrl", "").trimEnd('/')
            if (base.isEmpty()) null
            else base + "/userland-" + o.optString("channel", "canary")
        } catch (e: Throwable) { null }
    }

    private fun uncached(url: String): String =
        url + (if (url.indexOf('?') >= 0) "&" else "?") + "t=" + System.currentTimeMillis()

    internal fun httpGet(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = FETCH_TIMEOUT_MS
        conn.readTimeout = FETCH_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode + " " + url)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun anchorName(ctx: Context, field: String): String? {
        return try {
            val t = ctx.assets.open(CHANNEL_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(t).optString(field, "").ifBlank { null }
        } catch (e: Throwable) { null }
    }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\r", "").replace("\n", "").trim()
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }

    internal fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean {
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pemToDer(pubPem)))
            val v = Signature.getInstance("Ed25519")
            v.initVerify(key)
            v.update(data)
            v.verify(sig)
        } catch (e: Throwable) { false }
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder()
        for (b in d) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    internal fun unzipInto(zipBytes: ByteArray, dest: File) {
        dest.mkdirs()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val name = e.name
                if (name.contains("..")) { zin.closeEntry(); e = zin.nextEntry; continue }
                val out = File(dest, name)
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    ExecBits.apply(out)
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
    }

    private fun applyLinkFarm(root: File): Int {
        val farm = File(root, "link-farm.txt")
        if (!farm.isFile) return 0
        val inside = root.canonicalPath + File.separator
        var applied = 0
        for (line in farm.readLines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("\t")
            if (parts.size != 2) continue
            val rel = parts[0].trim()
            val target = parts[1].trim()
            if (rel.isEmpty() || target.isEmpty()) continue
            try {
                val dst = File(root, rel)
                val dstCanon = dst.canonicalPath
                if (!dstCanon.startsWith(inside) || dstCanon == root.canonicalPath) continue
                val resolved = File(dst.parentFile, target).canonicalPath
                if (!resolved.startsWith(inside) || resolved == root.canonicalPath) continue
                dst.parentFile?.mkdirs()
                dst.delete()
                Os.symlink(target, dst.absolutePath)
                applied++
            } catch (e: Throwable) { }
        }
        return applied
    }

    private fun farmBroken(root: File): Int {
        val farm = File(root, "link-farm.txt")
        if (!farm.isFile) return 0
        var broken = 0
        for (line in farm.readLines()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val rel = line.split("\t")[0].trim()
            if (rel.isEmpty()) continue
            val f = File(root, rel)
            try { if (!java.nio.file.Files.isSymbolicLink(f.toPath()) || !f.exists()) broken++ } catch (e: Throwable) { broken++ }
        }
        return broken
    }

    private class TrueName(val name: String, val entryRel: String)

    private fun aliasesOf(t: JSONObject, toolName: String): List<TrueName> {
        val ja = t.optJSONArray("aliases") ?: return emptyList()
        val out = mutableListOf<TrueName>()
        var k = 0
        while (k < ja.length()) {
            val o = ja.optJSONObject(k)
            k++
            if (o == null) throw IllegalArgumentException("aliases[" + (k - 1) + "] 不是对象（清单形状坏了）")
            val a = o.optString("name", "")
            val rel = o.optString("entry", "")
            if (a.isEmpty() || rel.isEmpty()) throw IllegalArgumentException("aliases[" + (k - 1) + "] 缺 name 或 entry")
            if (rel.startsWith("/") || rel.indexOf("..") >= 0 || !rel.contains("/")) {
                throw IllegalArgumentException("别名 " + a + " 的入口不是件内相对路径: " + rel)
            }
            if (a != toolName) out.add(TrueName(a, rel))
        }
        return out
    }

    private fun ensureEntry(
        ctx: Context,
        name: String,
        root: File,
        entryRel: String,
        aliases: List<TrueName>,
    ): String? {
        if (!linkEntry(ctx, name, root, entryRel, aliases)) return name
        if (!entryLink(ctx, name).isFile) return name
        for (a in aliases) if (!entryLink(ctx, a.name).isFile) return a.name
        return null
    }

    private fun linkEntry(ctx: Context, name: String, root: File, entryRel: String, aliases: List<TrueName>): Boolean {
        return try {
            val entry = File(root, entryRel)
            val link = entryLink(ctx, name)
            link.delete()
            Os.symlink(entry.absolutePath, link.absolutePath)
            for (a in aliases) {
                val la = entryLink(ctx, a.name)
                la.delete()
                Os.symlink(File(root, a.entryRel).absolutePath, la.absolutePath)
            }
            true
        } catch (e: Throwable) { false }
    }

    fun ensure(ctx: Context): Int {
        val base = manifestDir(ctx) ?: run {
            RuntimeDiagnostics.append(ctx, "supply", false, "C 层供给未启动", "assets/" + CHANNEL_ASSET + " 读不到通道锚")
            return 0
        }
        val pubPem = try {
            ctx.assets.open(PUBKEY_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(ctx, "supply", false, "C 层供给未启动", "assets/" + PUBKEY_ASSET + " 读不到信任根")
            return 0
        }
        val tc = toolchainDir(ctx)
        tc.mkdirs()
        try {
            val manName = anchorName(ctx, "manifestName")
            if (manName == null) {
                RuntimeDiagnostics.append(
                    ctx, "supply", false, "C 层供给未启动",
                    "assets/" + CHANNEL_ASSET + " 没有 manifestName：远端对象键只由通道锚声明，不回落旧键"
                )
                return 0
            }
            val sigName = anchorName(ctx, "sigName") ?: (manName + ".sig")
            val manifestBytes = httpGet(uncached(base + "/" + manName))
            val sigBytes = httpGet(uncached(base + "/" + sigName)).toString(Charsets.UTF_8).trim().let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
            if (!verifyEd25519(pubPem, manifestBytes, sigBytes)) {
                RuntimeDiagnostics.append(ctx, "supply", false, "C 层清单验签不通过", "拒装任何件")
                return 0
            }
            File(tc, MANIFEST_NAME).writeBytes(manifestBytes)
            val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
            val tools = manifest.optJSONArray("tools")
            if (tools == null) {
                RuntimeDiagnostics.append(ctx, "supply", false, "C 层清单没有 tools 数组（一件都没声明）", base)
                return 0
            }
            val declared = tools.length()
            val shortPieces = mutableListOf<String>()
            var available = 0
            var i = 0
            while (i < tools.length()) {
                val t = tools.getJSONObject(i)
                i++
                val name = t.optString("name", "")
                val url = t.optString("url", "")
                val want = t.optString("sha256", "")
                val entryRel = t.optString("entry", "bin/" + name)
                if (name.isEmpty() || url.isEmpty() || want.isEmpty()) {
                    val who = if (name.isEmpty()) "(无名)" else name
                    val miss = mutableListOf<String>()
                    if (url.isEmpty()) miss.add("url")
                    if (want.isEmpty()) miss.add("sha256")
                    val why = if (miss.isEmpty()) "字段缺失" else "缺 " + miss.joinToString("+")
                    RuntimeDiagnostics.append(
                        ctx, "supply", false,
                        "C 层清单里这件取不到（设备装不上）：" + who, why
                    )
                    shortPieces.add(who + "（" + why + "）")
                    continue
                }
                val marker = File(tc, "." + name + ".ok")
                val root = File(tc, name)
                var aliases: List<TrueName> = emptyList()
                var aliasErr: String? = null
                try {
                    aliases = aliasesOf(t, name)
                } catch (e: Throwable) {
                    aliasErr = e.message ?: e.javaClass.simpleName
                }
                if (aliasErr != null) {
                    RuntimeDiagnostics.append(ctx, "supply", false, "C 层清单里这件的 aliases 格读不出：" + name, aliasErr)
                    shortPieces.add(name + "（aliases 格坏）")
                    continue
                }
                if (marker.isFile && marker.readText().trim() == want && File(root, entryRel).isFile) {
                    val brokenLinks = farmBroken(root)
                    if (brokenLinks > 0) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层已就位件的链接农场有 " + brokenLinks + " 条不可解析", name)
                        shortPieces.add(name + "（农场 " + brokenLinks + " 条不可解析）")
                        continue
                    }
                    ExecBits.repair(root)
                    val broken = ensureEntry(ctx, name, root, entryRel, aliases)
                    if (broken != null) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层已就位件按真名调不到（\$PREFIX/bin 入口不可用）", name + " → " + broken)
                        shortPieces.add(name + "（真名 " + broken + " 不可用）")
                        continue
                    }
                    available++
                    continue
                }
                val staging = File(tc, "." + name + ".staging")
                try {
                    val bytes = httpGet(url)
                    val got = sha256Hex(bytes)
                    if (got != want) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层件 sha256 不符（已丢弃，不落位）", name + " " + got.take(12) + " != " + want.take(12))
                        shortPieces.add(name + "（sha256 不符）")
                        continue
                    }
                    staging.deleteRecursively()
                    unzipInto(bytes, staging)
                    val stagedEntry = File(staging, entryRel)
                    if (!stagedEntry.isFile) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层件缺入口（已丢弃）", name + " " + entryRel)
                        staging.deleteRecursively()
                        shortPieces.add(name + "（缺入口 " + entryRel + "）")
                        continue
                    }
                    applyLinkFarm(staging)
                    val brokenLinks = farmBroken(staging)
                    if (brokenLinks > 0) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层件链接农场有 " + brokenLinks + " 条没建成", name)
                    }
                    root.deleteRecursively()
                    if (!staging.renameTo(root)) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层件落位失败", name)
                        staging.deleteRecursively()
                        shortPieces.add(name + "（落位失败）")
                        continue
                    }
                    lobos.os.StateFiles.writeAtomic(marker, want)
                    if (brokenLinks > 0) {
                        shortPieces.add(name + "（农场 " + brokenLinks + " 条没建成）")
                        continue
                    }
                    val brokenFresh = ensureEntry(ctx, name, root, entryRel, aliases)
                    if (brokenFresh != null) {
                        RuntimeDiagnostics.append(ctx, "supply", false, "C 层件按真名调不到（\$PREFIX/bin 入口没建成）", name + " → " + brokenFresh)
                        shortPieces.add(name + "（真名 " + brokenFresh + " 不可用）")
                        continue
                    }
                    available++
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    val why = e.message ?: e.javaClass.simpleName
                    RuntimeDiagnostics.append(ctx, "supply", false, "C 层供给这件中断：" + name, why)
                    shortPieces.add(name + "（" + why + "）")
                }
            }
            val balanced = shortPieces.isEmpty()
            RuntimeDiagnostics.append(
                ctx, "supply", balanced,
                if (balanced) "C 层供给对账：声明 " + declared + " 件，全部按真名可用"
                else "C 层供给对账不平：声明 " + declared + " 件，可用 " + available + " 件",
                (if (balanced) "" else "缺：" + shortPieces.joinToString("；") + " ← ") + base
            )
            return available
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(ctx, "supply", false, "C 层供给异常", e.message ?: e.javaClass.simpleName)
            return 0
        }
    }
}
