package lobos.bridge

import android.content.Context
import android.system.Os
import lobos.os.Backoff
import lobos.os.RuntimeEnvironment
import lobos.runtime.NodeProvisioner
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object AdbClientRunner {

    data class AdbOutcome(
        val ok: Boolean,
        val json: JSONObject?,
        val error: String?,
        val raw: String,
        val exitCode: Int,
    )

    fun status(context: Context): AdbOutcome =
        serve(context, "status", JSONObject(), DEFAULT_TIMEOUT_MS)

    fun forget(context: Context): AdbOutcome =
        serve(context, "forget", JSONObject(), DEFAULT_TIMEOUT_MS)

    fun channel(context: Context, timeoutMs: Long = CHANNEL_TIMEOUT_MS): AdbOutcome =
        serve(context, "channel", JSONObject(), timeoutMs)

    fun pair(
        context: Context,
        host: String,
        pairPort: Int,
        code: String,
        connectPort: Int?,
        timeoutMs: Long,
    ): AdbOutcome {
        val args = mutableListOf("pair", "--host", host, "--pair-port", pairPort.toString(),
            "--code", code, "--timeout-ms", timeoutMs.toString())
        if (connectPort != null) args += listOf("--connect-port", connectPort.toString())
        return runOnce(context, args, timeoutMs + SPAWN_SLACK_MS)
    }

    fun shell(
        context: Context,
        cmd: String,
        host: String?,
        connectPort: Int?,
        timeoutMs: Long,
    ): AdbOutcome {
        val params = JSONObject().apply {
            put("cmd", cmd)
            put("timeoutMs", timeoutMs)
        }
        var epHost = host
        var epPort = connectPort
        if (epHost == null || epPort == null) {
            val ch = channel(context, CHANNEL_TIMEOUT_MS)
            val ready = ch.json?.takeIf { it.optBoolean("ready", false) }
            if (ready != null) {
                epHost = epHost ?: ready.optString("host", "").takeIf { it.isNotBlank() }
                epPort = epPort ?: ready.optInt("port", 0).takeIf { it > 0 }
            }
            if (epHost == null || epPort == null) {
                val live = ConnectEndpointResolver.resolve(context)
                epHost = epHost ?: live?.host
                epPort = epPort ?: live?.port
            }
        }
        epHost?.takeIf { it.isNotBlank() }?.let { params.put("host", it) }
        epPort?.takeIf { it > 0 }?.let { params.put("connectPort", it) }
        return serve(context, "shell", params, timeoutMs + SPAWN_SLACK_MS)
    }

    private sealed class CallResult {
        data class Frame(val obj: JSONObject) : CallResult()
        data class Failure(val error: String, val dead: Boolean) : CallResult()
    }

    private val lock = Any()

    @Volatile
    private var proc: ServeProcess? = null

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var nextStartAtMs = 0L

    @Volatile
    private var lastStartError: String? = null

    @Volatile private var consecutiveTimeouts = 0

    private fun onTimeout(context: Context, why: String) {
        consecutiveTimeouts += 1
        if (consecutiveTimeouts < TIMEOUT_MAX) return
        consecutiveTimeouts = 0
        synchronized(lock) {
            runCatching { proc?.destroy() }
            proc = null
        }
        lobos.os.Journal.note(context, "adb", false, "adb serve 连续超时：强制重启通道进程", why)
    }

    private fun serve(context: Context, method: String, params: JSONObject, timeoutMs: Long): AdbOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastErr: String? = null
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                onTimeout(context, method + " 超时 " + timeoutMs + "ms")
                return AdbOutcome(false, null, lastErr ?: ("adb-timeout " + timeoutMs + "ms"), drainLogs(), -1)
            }
            val p = ensureProcess(context, remaining)
                ?: return AdbOutcome(false, null, lastStartError ?: "adb serve 进程不可用", drainLogs(), -1)
            when (val r = p.call(method, params, deadline - System.currentTimeMillis())) {
                is CallResult.Frame -> {
                    markHealthy()
                    return toOutcome(r.obj, p)
                }
                is CallResult.Failure -> {
                    if (!r.dead) return AdbOutcome(false, null, r.error, p.logsText(), -1)
                    lastErr = r.error
                    if (System.currentTimeMillis() >= deadline) {
                        return AdbOutcome(false, null, lastErr, p.logsText(), -1)
                    }
                }
            }
        }
    }

    private fun ensureProcess(context: Context, budgetMs: Long): ServeProcess? {
        synchronized(lock) {
            val existing = proc
            if (existing != null && existing.alive) return existing
            if (existing != null) {
                existing.destroyQuietly()
                proc = null
                consecutiveFailures += 1
                nextStartAtMs = System.currentTimeMillis() + backoffMs(consecutiveFailures)
            }
            val waitMs = nextStartAtMs - System.currentTimeMillis()
            if (waitMs > 0) {
                if (waitMs >= budgetMs) {
                    lastStartError = "adb serve 处于重启退避中（" + waitMs + "ms 后重试）"
                    return null
                }
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return try {
                val started = startProcess(context)
                proc = started
                lastStartError = null
                started
            } catch (e: Throwable) {
                consecutiveFailures += 1
                nextStartAtMs = System.currentTimeMillis() + backoffMs(consecutiveFailures)
                lastStartError = "adb-spawn-failed " + e::class.java.simpleName + ": " + (e.message ?: "")
                null
            }
        }
    }

    private fun markHealthy() {
        consecutiveFailures = 0
        nextStartAtMs = 0L
    }

    private fun backoffMs(failures: Int): Long =
        Backoff.exponential(failures - 1, RESTART_BASE_MS, RESTART_MAX_MS)

    private fun envFor(context: Context, adbDir: File): Map<String, String> =
        RuntimeEnvironment.treeRootEnv(RuntimeEnvironment.treeRootFor(context), Os.getenv("PATH")) +
            mapOf("LOBOS_ADB_DIR" to adbDir.absolutePath)

    private fun startProcess(context: Context): ServeProcess? {
        val scriptDir = NodeProvisioner.ensureAdbClientScripts(context)
        val nodeBin = lobos.os.NodeRuntime.path(context) ?: run {
            lastStartError = lobos.os.NodeRuntime.missing(context)
            return null
        }
        val adbDir = File(context.filesDir, "adb").apply { if (!exists()) mkdirs() }
        val args = mutableListOf(nodeBin.absolutePath, File(scriptDir, "cli.js").absolutePath, "serve")
        args += listOf("--migrate-from", File(context.filesDir, "supervisor/adb").absolutePath)
        val spawned = ProcessSupervisor.spawn(
            command = args,
            cwd = context.filesDir,
            env = envFor(context, adbDir),
            envMode = ProcessSupervisor.ENV_MERGE,
            redirectErrorStream = false,
            owner = ProcessSupervisor.OWNER_ADB_CLIENT,
        )
        return ServeProcess(spawned.process)
    }

    private fun toOutcome(obj: JSONObject, p: ServeProcess): AdbOutcome {
        val raw = p.logsText()
        val err = obj.optJSONObject("error")
        if (err != null) {
            return AdbOutcome(false, null, err.optString("message", "unknown").ifBlank { "unknown" }, raw, 0)
        }
        val result = obj.optJSONObject("result")
            ?: return AdbOutcome(false, null, "serve 结果帧缺 result: " + obj.toString().take(200), raw, 0)
        val ok = result.optBoolean("ok", true)
        return AdbOutcome(
            ok = ok,
            json = result,
            error = if (ok) null else result.optString("error", "unknown").ifBlank { "unknown" },
            raw = raw,
            exitCode = 0,
        )
    }

    private fun drainLogs(): String = proc?.logsText() ?: ""

    private fun runOnce(context: Context, subArgs: List<String>, procTimeoutMs: Long): AdbOutcome {
        val scriptDir = try {
            NodeProvisioner.ensureAdbClientScripts(context)
        } catch (e: Throwable) {
            return AdbOutcome(false, null, "adb-client-script-missing: " + e.message, "", -1)
        }
        val nodeBin = lobos.os.NodeRuntime.path(context)
            ?: return AdbOutcome(false, null, lobos.os.NodeRuntime.missing(context), "", -1)
        val adbDir = File(context.filesDir, "adb").apply { if (!exists()) mkdirs() }

        val args = mutableListOf(nodeBin.absolutePath, File(scriptDir, "cli.js").absolutePath)
        args += subArgs
        args += listOf("--migrate-from", File(context.filesDir, "supervisor/adb").absolutePath)

        return try {
            val p = ProcessSupervisor.spawn(
                command = args,
                cwd = context.filesDir,
                env = envFor(context, adbDir),
                envMode = ProcessSupervisor.ENV_MERGE,
                redirectErrorStream = false,
                owner = ProcessSupervisor.OWNER_ADB_CLIENT,
            ).process
            val out = StringBuilder()
            val err = StringBuilder()
            val outPump = Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < MAX_OUTPUT) out.append(line).append('\n') }
                }
            }
            val errPump = Thread {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(err) { if (err.length < MAX_OUTPUT) err.append(line).append('\n') }
                }
            }
            outPump.start()
            errPump.start()
            val finished = p.waitFor(procTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                return AdbOutcome(false, null, "adb-timeout " + procTimeoutMs + "ms", out.toString() + err.toString(), -1)
            }
            outPump.join(1000)
            errPump.join(1000)
            val outText = synchronized(out) { out.toString() }
            val raw = outText + err.toString()
            val exit = p.exitValue()

            val line = outText.lineSequence().lastOrNull { it.startsWith(RESULT_PREFIX) }
                ?: return AdbOutcome(false, null,
                    "adb 进程未输出结果行（exit=" + exit + "）", raw, exit)
            val json = try {
                JSONObject(line.substring(RESULT_PREFIX.length).trim())
            } catch (e: Throwable) {
                return AdbOutcome(false, null, "结果行不是合法 JSON: " + line.take(200), raw, exit)
            }
            AdbOutcome(
                ok = json.optBoolean("ok", false) && exit == 0,
                json = json,
                error = if (json.optBoolean("ok", false)) null
                        else json.optString("error", "unknown").ifBlank { "unknown" },
                raw = raw,
                exitCode = exit,
            )
        } catch (e: Throwable) {
            AdbOutcome(false, null,
                "adb-spawn-failed " + e::class.java.simpleName + ": " + (e.message ?: ""), "", -1)
        }
    }

    private class ServeProcess(private val process: Process) {
        private val nextId = AtomicLong(0)
        private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
        private val logs = StringBuilder()
        private val writeLock = Any()
        private val stdout: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val stdin: BufferedWriter = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

        @Volatile
        var alive: Boolean = true
            private set

        @Volatile
        private var deathError: String? = null

        init {
            val out = Thread { readLoop() }
            out.name = "adb-serve-out"
            out.isDaemon = true
            out.start()
            val err = Thread { drainStderr() }
            err.name = "adb-serve-err"
            err.isDaemon = true
            err.start()
        }

        fun destroy() {
            alive = false
            runCatching { process.destroy() }
        }

        fun call(method: String, params: JSONObject, timeoutMs: Long): CallResult {
            if (!alive) return CallResult.Failure(deathError ?: "adb serve 进程已退出", true)
            val id = nextId.incrementAndGet()
            val future = CompletableFuture<JSONObject>()
            pending[id] = future
            val frame = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            try {
                synchronized(writeLock) {
                    stdin.write(frame.toString())
                    stdin.write("\n")
                    stdin.flush()
                }
            } catch (e: Throwable) {
                pending.remove(id)
                onExit("adb serve 写入失败: " + (e.message ?: e::class.java.simpleName))
                return CallResult.Failure(deathError ?: "adb serve 写入失败", true)
            }
            return try {
                CallResult.Frame(future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS))
            } catch (e: TimeoutException) {
                pending.remove(id)
                CallResult.Failure("adb-timeout " + timeoutMs + "ms（serve 未回帧）", false)
            } catch (e: ExecutionException) {
                pending.remove(id)
                CallResult.Failure(deathError ?: ("adb serve 中断: " + (e.cause?.message ?: "")), true)
            } catch (e: InterruptedException) {
                pending.remove(id)
                Thread.currentThread().interrupt()
                CallResult.Failure("adb serve 调用被中断", false)
            }
        }

        fun logsText(): String = synchronized(logs) { logs.toString() }

        private fun readLoop() {
            try {
                while (true) {
                    val line = stdout.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = try {
                        JSONObject(line)
                    } catch (e: Throwable) {
                        appendLog("stdout非帧: " + line.take(200))
                        continue
                    }
                    val id = obj.optLong("id", -1L)
                    if (id > 0) pending.remove(id)?.complete(obj)
                    else appendLog("stdout无id帧: " + line.take(200))
                }
            } catch (e: Throwable) {
                appendLog("stdout读取结束: " + (e.message ?: e::class.java.simpleName))
            } finally {
                onExit("adb serve stdout 关闭")
            }
        }

        private fun drainStderr() {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { appendLog(it) }
            } catch (e: Throwable) {
            }
        }

        private fun onExit(reason: String) {
            if (!alive) return
            alive = false
            deathError = reason
            for ((_, f) in pending) f.completeExceptionally(IllegalStateException(reason))
            pending.clear()
        }

        fun destroyQuietly() {
            onExit("adb serve 进程被替换")
            try { stdin.close() } catch (e: Throwable) {   }
            try { process.destroy() } catch (e: Throwable) {   }
        }

        private fun appendLog(message: String) {
            synchronized(logs) {
                logs.append(message).append('\n')
                if (logs.length > MAX_LOG_CHARS) logs.delete(0, logs.length - MAX_LOG_CHARS)
            }
        }
    }

    private const val RESULT_PREFIX = "LOBOS_ADB_RESULT "
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val TIMEOUT_MAX = 3
    private const val CHANNEL_TIMEOUT_MS = 2_000L
    private const val SPAWN_SLACK_MS = 10_000L
    private const val RESTART_BASE_MS = 500L
    private const val RESTART_MAX_MS = 30_000L
    private const val MAX_OUTPUT = 64 * 1024
    private const val MAX_LOG_CHARS = 8 * 1024
}
