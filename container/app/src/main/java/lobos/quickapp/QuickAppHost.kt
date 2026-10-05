package lobos.quickapp

import android.app.Activity
import android.content.Context
import android.util.Log
import com.didi.dimina.Dimina
import com.didi.dimina.bean.MiniProgram
import java.io.File
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

    fun install(id: String, packageDir: File, completion: (Result<org.json.JSONObject>) -> Unit) {
        val dimina = runCatching { Dimina.getInstance() }.getOrElse {
            completion(Result.failure(IllegalStateException("快应用运行时未初始化")))
            return
        }
        val entry = packageDir.takeIf { it.isDirectory }
            ?: run {
                completion(Result.failure(IllegalStateException("程序包目录不存在: $packageDir")))
                return
            }
        dimina.installMiniProgram(id, entry.absolutePath) { r ->
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
        val dir = File(entry.stateDir, "quickapp")
        val mp = MiniProgram(
            appId = id,
            name = id,
            path = null,
            versionName = entry.version,
        )
        runCatching {
            dimina.installMiniProgram(id, dir.absolutePath) { r ->
                if (r.isFailure) {
                    Log.e(TAG, "装入失败，跳过打开: $id", r.exceptionOrNull())
                    return@installMiniProgram
                }
                runCatching { dimina.startMiniProgram(activity, mp) }
                    .onFailure { Log.e(TAG, "打开失败: $id", it) }
            }
        }.onFailure { Log.e(TAG, "调 dimina 失败: $id", it) }
    }

    fun close(id: String): Boolean = runCatching { Dimina.getInstance().closeMiniProgram(id) }.getOrDefault(false)

    fun hide(id: String): Boolean = runCatching { Dimina.getInstance().hideMiniProgram(id) }.getOrDefault(false)

    fun syncAll(context: Context): Int {
        val dimina = runCatching { Dimina.getInstance() }.getOrElse { return 0 }
        var n = 0
        for (e in ProgramIndex.all(context)) {
            if (e.level != lobos.os.Level.APPLICATION) continue
            val dir = File(e.stateDir, "quickapp")
            if (!dir.isDirectory) continue
            dimina.installMiniProgram(e.id, dir.absolutePath) {}
            n++
        }
        return n
    }
}
