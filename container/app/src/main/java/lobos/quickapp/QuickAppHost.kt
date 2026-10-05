package lobos.quickapp

import android.app.Activity
import android.content.Context
import android.util.Log
import com.didi.dimina.Dimina
import com.didi.dimina.bean.MiniProgram
import java.io.File
import lobos.os.PortBroker
import lobos.os.ProgramIndex

object QuickAppHost {

    private const val TAG = "lobos.quickapp"
    private const val MODULE = "lobos"

    fun ready(): Boolean = runCatching { Dimina.getInstance() }.isSuccess

    fun registerCapabilities() {
        val dimina = runCatching { Dimina.getInstance() }.getOrElse {
            Log.e(TAG, "注册能力失败：运行时未初始化", it)
            return
        }
        dimina.registerExtModule(MODULE) { event, data, callback ->
            LobosBridge.handle(event, data) { payload ->
                runCatching { callback.onSuccess(payload) }
            }
            null
        }
        Log.i(TAG, "已注册能力模块 extBridge(module=$MODULE)")
    }

    fun installed(id: String): Boolean = runCatching { Dimina.getInstance().isExistsApp(id) }.getOrDefault(false)

    fun install(id: String, packageDir: File, port: Int, completion: (Result<org.json.JSONObject>) -> Unit) {
        val dimina = runCatching { Dimina.getInstance() }.getOrElse {
            completion(Result.failure(IllegalStateException("快应用运行时未初始化")))
            return
        }
        if (!packageDir.isDirectory) {
            completion(Result.failure(IllegalStateException("前端目录不存在: $packageDir")))
            return
        }
        val checked = QuickAppPackage.check(packageDir, id)
        if (!checked.ok) {
            completion(Result.failure(IllegalStateException("前端包结构不合格：${checked.problem}")))
            return
        }
        val endpoint = "http://127.0.0.1:$port"
        if (!QuickAppPackage.withEndpoint(packageDir, endpoint, port)) {
            completion(Result.failure(IllegalStateException("无法把后端地址写进 config.json")))
            return
        }
        Log.i(TAG, "前端包校验通过 $id v${checked.versionCode}，后端地址 $endpoint 已注入")
        dimina.installMiniProgram(id, packageDir.absolutePath) { r ->
            if (r.isSuccess) Log.i(TAG, "快应用已装入 dimina: $id") else Log.e(TAG, "装入失败: $id", r.exceptionOrNull())
            completion(r)
        }
    }

    fun open(activity: Activity, id: String) {
        val dimina = runCatching { Dimina.getInstance() }.getOrElse {
            Log.e(TAG, "快应用运行时未初始化，无法打开 $id", it)
            return
        }
        val entry = ProgramIndex.get(activity, id)
        if (entry == null) {
            Log.e(TAG, "程序不在索引里: $id")
            return
        }
        if (!installed(id)) {
            Log.e(TAG, "前端尚未装入 dimina，先装后开: $id")
            return
        }
        val mp = MiniProgram(
            appId = id,
            name = id,
            path = null,
            versionName = entry.version,
        )
        runCatching { dimina.startMiniProgram(activity, mp) }
            .onSuccess { Log.i(TAG, "已打开快应用: $id") }
            .onFailure { Log.e(TAG, "打开失败: $id", it) }
    }

    fun close(id: String): Boolean = runCatching { Dimina.getInstance().closeMiniProgram(id) }.getOrDefault(false)

    fun hide(id: String): Boolean = runCatching { Dimina.getInstance().hideMiniProgram(id) }.getOrDefault(false)

    fun syncAll(context: Context): Int {
        var n = 0
        for (e in ProgramIndex.all(context)) {
            if (e.level != lobos.os.Level.APPLICATION) continue
            val dir = quickAppDirOf(e.stateDir)
            if (!dir.isDirectory) continue
            val port = PortBroker.claim(context, e.id)
            if (port <= 0) continue
            val ok = QuickAppPackage.check(dir, e.id).ok &&
                QuickAppPackage.withEndpoint(dir, "http://127.0.0.1:$port", port)
            if (!ok) {
                Log.e(TAG, "前端包不合格，跳过: ${e.id}")
                continue
            }
            runCatching { Dimina.getInstance().installMiniProgram(e.id, dir.absolutePath) {} }
                .onSuccess { n++ }
                .onFailure { Log.e(TAG, "装入失败: ${e.id}", it) }
        }
        return n
    }

    fun quickAppDirOf(stateDir: String): File = File(stateDir, "quickapp")
}
