package lobos.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Environment
import android.util.Log
import lobos.BuildConfig
import lobos.ProvisioningProbe
import lobos.R
import lobos.RuntimeDiagnostics
import lobos.capability.BridgeTokens
import lobos.capability.CapabilityCatalog
import lobos.capability.CapabilityEvidenceCollector
import lobos.lifecycle.AccessibilityServiceState
import lobos.os.Desired
import lobos.os.IndexEntry
import lobos.os.Level
import lobos.os.ProgramIndex
import lobos.os.ProgramManager
import lobos.ota.ProgramInstaller
import lobos.ota.ProgramDir
import lobos.ota.ProgramOtaUpdater
import lobos.lifecycle.OsHostService
import lobos.lifecycle.OsAccessibilityService
import lobos.native.NativeAssetRegistry
import lobos.native.NativePreparer
import lobos.native.PrepareReport
import lobos.os.CatalogClient
import lobos.os.Journal
import lobos.os.OsInit
import lobos.os.OsPhase
import lobos.os.PackageInstaller
import lobos.os.PortBroker
import lobos.os.ProgramSettings
import lobos.os.RegistryStore
import lobos.os.TaskRegistry
import lobos.runtime.InstanceHost
import lobos.runtime.GuestAdapter
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

class CapabilityBroker(private val host: Service) : ContextWrapper(host) {

    private val servers = java.util.concurrent.ConcurrentHashMap<String, LocalServerSocket>()
    private var running = false
    private var socketName: String = GuestAdapter.BRIDGE_SOCKET
    @Volatile private var activeConnections = 0
    private val executor = Executors.newCachedThreadPool()
    private lateinit var notifManager: NotificationManager

    fun start() {
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        live = this
        startBridge()
        OsHostService.ensureRunning(this)
    }

    fun onHostStart(intent: Intent?) {
        if (!running) startBridge()
    }

    private fun startBridge() {
        if (running) return
        running = true
        listenLoop(socketName)
    }

    @Synchronized
    private fun listenLoop(name: String) {
        if (servers.containsKey(name)) return
        executor.execute {
            val srv = try {
                LocalServerSocket(name)
            } catch (e: Throwable) {
                RuntimeDiagnostics.append(this, "bridge", false, "HostBridge 监听失败", "name=" + name + " " + e::class.java.simpleName + ": " + e.message)
                return@execute
            }
            servers[name] = srv
            RuntimeDiagnostics.append(this, "bridge", true, "HostBridge 监听 UDS（会话名）", "name=" + name)
            while (running && servers[name] === srv) {
                val sock = try { srv.accept() } catch (_: Throwable) { null } ?: break
                executor.execute { handleConnection(sock, name) }
            }
            servers.remove(name)
            try { srv.close() } catch (_: Throwable) {}
        }
    }

    @Synchronized
    fun beginSession(programId: String, generation: Long): String {
        val token = lobos.os.SessionRegistry.issue(this, programId, generation)
        val name = lobos.os.SessionRegistry.socketName(token)
        if (running && !servers.containsKey(name)) listenLoop(name)
        return token
    }

    private fun sessionOfSocket(socketName: String): lobos.os.SessionRegistry.Session? {
        val s = lobos.os.SessionRegistry.list(this)
            .firstOrNull { lobos.os.SessionRegistry.socketName(it.token) == socketName } ?: return null
        return lobos.os.SessionRegistry.verify(this, s.token)
    }

    private class SessionHolder(val socketName: String) {
        @Volatile
        var granted: Set<String> = emptySet()
        @Volatile
        var system: Boolean = false
        @Volatile var session: lobos.os.SessionRegistry.Session? = null
    }

    private fun handleConnection(sock: LocalSocket, socketName: String) {
        activeConnections += 1
        try {
            if (activeConnections > MAX_CONNECTIONS) {
                audit("bridge.reject", JSONObject(), false, "并发连接超限: " + activeConnections, null)
                return
            }
            val reader = BufferedReader(InputStreamReader(sock.inputStream))
            val out = sock.outputStream
            val holder = SessionHolder(socketName)
            val sb = StringBuilder()
            while (running) {
                val ok = try {
                    readFrameBounded(reader, MAX_FRAME_CHARS, sb)
                } catch (e: Throwable) {
                    audit("bridge.reject", JSONObject(), false, "帧超限或读取失败: " + e.message, null)
                    false
                }
                if (!ok) break
                val text = sb.toString().trim()
                if (text.isEmpty()) continue
                try {
                    val msg = JSONObject(text)
                    val resp = try {
                        dispatch(msg, holder)
                    } catch (e: Throwable) {
                        audit("bridge.reject", JSONObject(), false, "分发异常（必定回帧）: " + e.message, holder.session)
                        error(msg.opt("id"), CODE_INTERNAL, e::class.java.simpleName + ": " + (e.message ?: ""))
                    }
                    if (resp != null) writeFrame(out, resp)
                } catch (e: Throwable) {
                    Log.w(TAG, "帧解析失败", e)
                }
            }
        } catch (_: Throwable) {
        } finally {
            activeConnections -= 1
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun readFrameBounded(r: Reader, cap: Int, out: StringBuilder): Boolean {
        out.setLength(0)
        val buf = CharArray(4096)
        while (true) {
            val n = r.read(buf)
            if (n < 0) return out.isNotEmpty()
            for (i in 0 until n) {
                val c = buf[i]
                if (c == '\n') return true
                if (out.length >= cap) throw IllegalStateException("frame-too-large")
                out.append(c)
            }
        }
    }

    private fun writeFrame(out: OutputStream, obj: JSONObject) {
        val text = obj.toString()
        val frame = if (text.length > MAX_FRAME_CHARS) {
            audit("bridge.reject", JSONObject(), false, "响应超帧上限: " + text.length, null)
            error(
                obj.opt("id"), CODE_POLICY_DENIED,
                "响应超过帧上限（" + text.length + " > " + MAX_FRAME_CHARS + " 字符）：拒绝发送；请改用分页或落盘后读取",
            ).toString()
        } else {
            text
        }
        out.write((frame + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun dispatch(msg: JSONObject, holder: SessionHolder): JSONObject? {
        val id = msg.opt("id")
        val method = msg.optString("method", null)
        if (method == null) return null

        if (method == "bridge.handshake") {
            return handshake(id, msg.optJSONObject("params") ?: JSONObject(), holder)
        }

        val params = msg.optJSONObject("params") ?: JSONObject()
        val canonical = if (method == "notify.post") "notif.post" else method
        val def = METHODS[canonical] ?: OS_METHODS[canonical]
        if (def == null) {
            val alias = ApiSpec.deprecatedInFavorOf(method)
            if (alias != null && (METHODS[alias] != null || OS_METHODS[alias] != null)) {
                return error(id, CODE_METHOD_NOT_FOUND, ApiSpec.deprecationNotice(method) ?: "")
            }
            return error(id, CODE_METHOD_NOT_FOUND, "未知方法: $method")
        }
        val session = holder.session
            ?: return error(id, CODE_SESSION_MISSING, "未建立会话：先做 bridge.handshake")
        if (ApiSpec.scopeOf(canonical) == ApiSpec.SCOPE_SYSTEM && !holder.system) {
            return error(
                id, CODE_POLICY_DENIED,
                "该方法是系统作用域，程序会话不可调用：" + ApiSpec.canonical(canonical),
            )
        }
        val missing = def.caps.filterNot { holder.granted.contains(it) }
        if (missing.isNotEmpty()) {
            val undeclared = missing.filterNot { it in declaredCapabilities(holder.session?.programId ?: "") }
            val why = if (undeclared.isEmpty()) {
                "程序未在 manifest 声明：manifest.capabilities 需要显式申请"
            } else {
                "已声明但系统未实测到：需先在系统侧开通对应能力"
            }
            if (def.audit) audit(method, params, false, "缺少能力组：" + missing.joinToString(), session)
            return error(
                id, CODE_CAPABILITY_MISSING,
                "缺少能力组：" + missing.joinToString() + "（$why；当前授权：" + holder.granted.joinToString() + "）",
            )
        }
        try {
            val result = def.handle(params, holder.session?.programId ?: "")
            if (def.audit) audit(method, params, true, null, session)
            return ok(id, result)
        } catch (e: BridgeError) {
            if (def.audit) audit(method, params, false, e.message, session)
            return error(id, e.code, e.message ?: "error")
        } catch (e: Throwable) {
            if (def.audit) audit(method, params, false, e.message, session)
            Log.e(TAG, "方法执行异常: $method", e)
            return error(id, CODE_INTERNAL, "${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun handshake(id: Any?, params: JSONObject, holder: SessionHolder): JSONObject {
        val clientProtocol = params.optInt("protocol", 0)
        if (clientProtocol < PROTOCOL_MIN) {
            audit("bridge.handshake", params, false, "协议不兼容: client=" + clientProtocol, null)
            return error(
                id, CODE_PROTOCOL_UNSUPPORTED,
                "桥协议不兼容：内核支持 [" + PROTOCOL_MIN + "," + BuildConfig.BRIDGE_PROTOCOL + "]；客户端=" + clientProtocol,
            )
        }
        val negotiated = if (clientProtocol > BuildConfig.BRIDGE_PROTOCOL) BuildConfig.BRIDGE_PROTOCOL else clientProtocol
        val requires = params.optJSONArray("requires")?.toList() ?: emptyList()
        val hint = params.optString("program", "").takeIf { it.isNotBlank() }
        val session = sessionOfSocket(holder.socketName)
        if (session == null) {
            audit("bridge.handshake", params, false, "无有效内核会话", null)
            return error(id, CODE_SESSION_MISSING, "无有效内核会话：内核未签发或会话已失效")
        }
        val provided = params.optString("token", "").trim()
        if (provided.isBlank()) {
            audit("bridge.handshake", params, false, "缺少会话令牌", null)
            return error(id, CODE_SESSION_MISSING, "缺少会话令牌：握手必须携带环境变量 LOBOS_SESSION_TOKEN")
        }
        if (provided != session.token) {
            audit("bridge.handshake", params, false, "会话令牌不符", session)
            return error(id, CODE_SESSION_MISSING, "会话令牌不符：令牌必须来自内核注入的 LOBOS_SESSION_TOKEN")
        }
        if (holder.session != null) {
            audit("bridge.handshake", params, false, "该连接已激活会话（不允许重复握手）", session)
            return error(id, CODE_SESSION_MISSING, "该连接已完成握手：会话不允许重复激活")
        }
        if (!lobos.os.SessionRegistry.claim(this, session.token)) {
            audit("bridge.handshake", params, false, "会话已被占用（一次性激活）", session)
            return error(id, CODE_SESSION_MISSING, "会话已被占用：每个会话只允许激活一次")
        }
        holder.session = session
        val granted = serverGranted(session).toList()
        holder.granted = granted.toSet()
        holder.system = granted.contains(ApiSpec.GROUP_SYS)
        val caps = deviceCapabilities()
        audit("bridge.handshake", params, true, null, session)
        return ok(id, JSONObject().apply {
            put("protocol", negotiated)
            put("minProtocol", PROTOCOL_MIN)
            put("maxProtocol", BuildConfig.BRIDGE_PROTOCOL)
            put("compatible", clientProtocol <= BuildConfig.BRIDGE_PROTOCOL)
            put("capabilities", JSONArray(caps))
            put("groups", JSONArray(granted))
            put("program", session.programId)
            put("identity", "os-session")
            put("generation", session.generation)
            put("programHint", hint ?: JSONObject.NULL)
            put("authorizedGroups", JSONArray(granted))
        })
    }

    private fun serverGranted(session: lobos.os.SessionRegistry.Session): Set<String> {
        val base = runCatching {
            lobos.capability.BridgeTokens.from(CapabilityEvidenceCollector.systemReads(this))
        }.getOrDefault(setOf(lobos.capability.BridgeTokens.BASE))
        val isSystem = !isProgramSession(session)
        if (isSystem) return base + ApiSpec.GROUP_SYS
        val want = declaredCapabilities(session.programId)
        return base - sensitiveTokens() + base.filter { it in want }
    }

    private fun sensitiveTokens(): Set<String> = runCatching {
        lobos.capability.CapabilityCatalog.ALL.mapNotNull { it.bridgeToken }.toSet()
    }.getOrDefault(emptySet()) - setOf(
        lobos.capability.BridgeTokens.BASE,
        lobos.capability.BridgeTokens.PROGRAM_UPDATE,
    )

    private fun declaredCapabilities(programId: String): Set<String> {
        val id = programId.trim()
        if (id.isBlank()) return emptySet()
        val declared = runCatching { lobos.os.ProgramIndex.get(this, id)?.capabilities }
            .getOrNull().orEmpty()
        val known = runCatching {
            lobos.capability.CapabilityCatalog.ALL.mapNotNull { it.bridgeToken }.toSet() +
                lobos.capability.BridgeTokens.BASE
        }.getOrDefault(emptySet())
        return declared.filter { it in known }.toSet()
    }

    private fun isProgramSession(session: lobos.os.SessionRegistry.Session): Boolean {
        val id = session.programId.trim()
        if (id.isBlank()) return false
        val declared = lobos.os.ProgramRegistry.listIds(this)
        if (declared.isEmpty()) return true
        return declared.contains(id)
    }

    private fun packagesAction(p: JSONObject): JSONObject {
        val name = p.optString("name", "").trim()
        val action = p.optString("action", "").trim()
        if (ProgramIndex.safeSegment(name) == null) {
            throw BridgeError(CODE_INVALID_PARAM, "包名非法（只接受字母数字与 . _ -）：" + name.take(40))
        }
        if (name.isBlank() || action.isBlank()) {
            throw BridgeError(CODE_INVALID_PARAM, "需要 name 与 action（install|upgrade|enable|disable|uninstall|rollback）")
        }
        return when (action) {
            "enable", "disable" -> JSONObject().apply {
                put("ok", ProgramManager.setEnabled(this@CapabilityBroker, name, action == "enable"))
            }
            "uninstall" -> {
                if ((ProgramIndex.get(this@CapabilityBroker, name)?.tier == "base")) {
                    throw BridgeError(
                        CODE_POLICY_DENIED,
                        "系统基础环境不可卸载（可升级、可回退到 APK 基线）：" + name,
                    )
                }
                JSONObject().apply { put("ok", PackageInstaller.uninstall(this@CapabilityBroker, name)) }
            }
            "rollback" -> {
                if (!(ProgramIndex.get(this@CapabilityBroker, name)?.tier == "base")) {
                    throw BridgeError(CODE_POLICY_DENIED, "只有系统基础环境支持回退到 APK 基线：" + name)
                }
                JSONObject().apply { put("ok", PackageInstaller.rollbackToBaseline(this@CapabilityBroker, name)) }
            }
            "install", "upgrade" -> PackageInstaller.install(
                this@CapabilityBroker, name, p.optString("version", "").takeIf { it.isNotBlank() },
            )
            else -> throw BridgeError(CODE_INVALID_PARAM, "未知 action：" + action)
        }
    }

    private fun deviceCapabilities(): Set<String> =
        BridgeTokens.from(CapabilityEvidenceCollector.systemReads(this))

    private fun audit(
        method: String,
        params: JSONObject,
        ok: Boolean,
        err: String?,
        session: lobos.os.SessionRegistry.Session?,
    ) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val summary = params.toString().let { if (it.length > 200) it.take(200) + "…" else it }
            val who = if (session == null) {
                "session=(none) program=(none) generation=0 identity=(none)"
            } else {
                "session=" + session.id + " program=" + session.programId +
                    " generation=" + session.generation + " identity=os-session"
            }
            val line = ts + " " + who + " method=" + method + " ok=" + ok + " params=" + summary +
                (if (err != null) " err=" + err else "") + "\n"
            lobos.os.StateFiles.appendBounded(File(filesDir, "bridge-audit.log"), line)
        } catch (_: Throwable) {}
    }

    private fun ok(id: Any?, result: JSONObject): JSONObject =
        JSONObject().apply { put("jsonrpc", "2.0"); put("id", id); put("result", result) }

    private fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", JSONObject().apply { put("code", code); put("message", message) })
        }

    private fun requireA11y(): OsAccessibilityService =
        OsAccessibilityService.instance
            ?: throw BridgeError(CODE_CAPABILITY_MISSING, "无障碍服务未连接（请在系统设置中开启 Lob OS 无障碍服务）")

    private fun programJson(e: IndexEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.id)
        put("version", e.version.ifBlank { JSONObject.NULL })
        put("role", e.role)
        put("phase", e.desired.name.lowercase(Locale.US))
    }

    private fun programsJson(): JSONArray = JSONArray().apply {
        ProgramIndex.byLevel(this@CapabilityBroker, Level.APPLICATION).forEach { put(programJson(it)) }
    }

    private fun journalJson(e: Journal.Event): JSONObject = JSONObject().apply {
        put("seq", e.seq)
        put("ts", e.atMs)
        put("type", e.category)
        put("source", "os")
        put("data", e.detail)
        put("internal", false)
    }

    private fun toHost(action: String) {
        val i = Intent(this, OsHostService::class.java).setAction(action)
        startService(i)
    }

    private fun instanceJson(e: IndexEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("kind", "program")
        put("name", e.id)
        put("desired", e.desired.name.lowercase(Locale.US))
        put("phase", e.desired.name.lowercase(Locale.US))
    }

    private fun taskJson(x: TaskRegistry.Task): JSONObject = JSONObject().apply {
        put("id", x.id)
        put("kind", x.kind)
        put("state", x.state)
        put("progress", x.progress)
        put("startedAt", x.startedAt)
        put("detail", x.detail)
        if (x.endedAt != null) put("endedAt", x.endedAt)
    }

    private fun startProgramJob(kind: String, target: String): JSONObject {
        if (target.isBlank()) {
            throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：内核不接受\"默认程序\"")
        }
        val id = TaskRegistry.start(this@CapabilityBroker, kind)
        Thread {
            try {
                TaskRegistry.update(this@CapabilityBroker, id, "running", 10, "检查远端 program-manifest.json")
                val out = ProgramOtaUpdater.checkAndUpdate(
                    this@CapabilityBroker,
                    ProgramDir(this@CapabilityBroker, target),
                    checkOnly = false,
                )
                val ok = out.updated || !out.available
                TaskRegistry.finish(this@CapabilityBroker, id, ok, out.detail)
                Journal.append(this@CapabilityBroker, "appmgr", null, kind + "：" + out.detail)
            } catch (e: Throwable) {
                TaskRegistry.finish(this@CapabilityBroker, id, false, e::class.java.simpleName + ": " + e.message)
            }
        }.start()
        return JSONObject().apply { put("ok", true); put("accepted", true); put("taskId", id) }
    }

    private fun startUninstallJob(target: String): JSONObject {
        val id = TaskRegistry.start(this@CapabilityBroker, "uninstall")
        Thread {
            try {
                if (target.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：内核不接受\"默认程序\"")
                val km = ProgramDir(this@CapabilityBroker, target)
                val cur = km.currentVersion()
                val removable = km.installedVersions().filter { it != cur }
                if (removable.isEmpty()) {
                    TaskRegistry.finish(
                        this@CapabilityBroker, id, false,
                        "拒绝卸载：仅存 1 个版本（" + (cur ?: "无") + "），删掉就没有可运行程序",
                    )
                    Journal.note(
                        this@CapabilityBroker, "appmgr", false, "uninstall 被拒：保护最后可用版本",
                        "current=" + (cur ?: "无"),
                    )
                    return@Thread
                }
                TaskRegistry.update(this@CapabilityBroker, id, "running", 30, "清理 " + removable.size + " 个非当前版本")
                var removed = 0
                removable.forEach { v -> if (runCatching { km.programDir(v).deleteRecursively() }.getOrDefault(false)) removed++ }
                val stale = km.sweepStaleStaging().first
                TaskRegistry.finish(
                    this@CapabilityBroker, id, true,
                    "已卸载 " + removed + " 个非当前版本（保留 " + (cur ?: "无") + "），清扫暂存 " + stale.size + " 个",
                )
                Journal.append(
                    this@CapabilityBroker, "appmgr", null,
                    "uninstall：移除 " + removed + " 个非当前版本，current 未动",
                )
            } catch (e: Throwable) {
                TaskRegistry.finish(this@CapabilityBroker, id, false, e::class.java.simpleName + ": " + e.message)
            }
        }.start()
        return JSONObject().apply { put("ok", true); put("accepted", true); put("taskId", id) }
    }

    private val OS_METHODS: Map<String, MethodDef> = mapOf(
        "os.state.get" to MethodDef(listOf("base"), false) { _, _programId ->
            val s = OsInit.snapshot(this@CapabilityBroker)
            val since = s.atMs
            JSONObject().apply {
                put("phase", s.phase.name.lowercase(Locale.US))
                put("label", s.phase.label)
                put("since", since)
                put("uptimeMs", if (since > 0) System.currentTimeMillis() - since else 0L)
                put("degraded", s.phase == OsPhase.DEGRADED)
                put("statusLine", OsInit.statusLine(this@CapabilityBroker))
                put("facts", JSONObject().apply {
                    put("readingsCollected", s.facts.readingsCollected)
                    put("controlPlaneUp", s.facts.controlPlaneUp)
                    put("channel", s.facts.channel.name.lowercase(Locale.US))
                })
                put("programs", programsJson())
            }
        },
        "os.journal.read" to MethodDef(listOf("base"), false) { p, _programId ->
            val after = p.optLong("after", 0L)
            val limit = p.optInt("limit", 50).coerceIn(1, 1000)
            val evs = Journal.events(this@CapabilityBroker, limit).filter { it.seq > after }
            JSONObject().apply {
                put("seq", Journal.latestSeq(this@CapabilityBroker))
                put("events", JSONArray().apply { evs.forEach { put(journalJson(it)) } })
            }
        },
        "os.journal.logTail" to MethodDef(listOf("base"), false) { p, _programId ->
            val stream = p.optString("stream", "os")
            val n = p.optInt("n", 8).coerceIn(1, 200)
            val src = if (stream == "programs" || stream == "error") {
                RuntimeDiagnostics.readNodeStderr(this@CapabilityBroker)
            } else {
                Journal.tail(this@CapabilityBroker, n)
            }
            JSONObject().apply {
                put("stream", stream)
                put("lines", JSONArray().apply { src.split("\n").takeLast(n).forEach { put(it) } })
            }
        },
        "os.journal.export" to MethodDef(listOf("base"), false) { p, _programId ->
            val after = p.optLong("after", 0L)
            val limit = p.optInt("limit", 1000).coerceIn(1, 5000)
            val evs = Journal.events(this@CapabilityBroker, limit).filter { it.seq > after }
            JSONObject().apply {
                put("seq", Journal.latestSeq(this@CapabilityBroker))
                put("exported", evs.size)
                put("lines", JSONArray().apply { evs.forEach { put(it.toJson().toString()) } })
            }
        },
        "os.journal.metrics" to MethodDef(listOf("base"), false) { _, _programId ->
            val evs = Journal.events(this@CapabilityBroker, 1000)
            val bySource = JSONObject()
            val byType = JSONObject()
            var last = 0L
            evs.forEach { e ->
                bySource.put("os", bySource.optInt("os", 0) + 1)
                byType.put(e.category, byType.optInt(e.category, 0) + 1)
                if (e.atMs > last) last = e.atMs
            }
            val topTypes = JSONArray()
            byType.keys().asSequence().sortedByDescending { byType.optInt(it, 0) }.take(5)
                .forEach { k -> topTypes.put(JSONObject().apply { put("type", k); put("count", byType.optInt(k, 0)) }) }
            JSONObject().apply {
                put("gseq", Journal.latestSeq(this@CapabilityBroker))
                put("events", evs.size)
                put("bySource", bySource)
                put("topTypes", topTypes)
                put("sinceLastMs", if (last > 0) System.currentTimeMillis() - last else 0L)
            }
        },
        "os.journal.tasks" to MethodDef(listOf("base"), false) { p, _programId ->
            val kind = p.optString("kind", "").takeIf { it.isNotBlank() }
            val running = p.optBoolean("running", false)
            JSONObject().apply {
                put("tasks", JSONArray().apply {
                    TaskRegistry.list(this@CapabilityBroker, kind, running).forEach { put(taskJson(it)) }
                })
                put("current", TaskRegistry.current(this@CapabilityBroker, kind)?.let { taskJson(it) } ?: JSONObject.NULL)
            }
        },
        "os.journal.task" to MethodDef(listOf("base"), false) { p, _programId ->
            val id = p.optString("id", "")
            val task = TaskRegistry.get(this@CapabilityBroker, id)
                ?: throw BridgeError(CODE_METHOD_NOT_FOUND, "无此任务: " + id)
            JSONObject().apply { put("task", taskJson(task)) }
        },
        "os.instances.list" to MethodDef(listOf("base"), false) { _, _programId ->
            val mods = JSONArray()
            ProgramIndex.byLevel(this@CapabilityBroker, Level.APPLICATION).forEach { e ->
                val ph = e.desired.name.lowercase(Locale.US)
                mods.put(JSONObject().apply {
                    put("id", e.id); put("kind", "program"); put("name", e.id)
                    put("desired", ph); put("phase", ph)
                })
            }
            JSONObject().apply { put("modules", mods) }
        },
        "os.instances.get" to MethodDef(listOf("base"), false) { p, _programId ->
            val id = p.optString("id", "")
            if (id.isBlank()) {
                throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id（内核没有\"主程序\"概念）")
            }
            val e = ProgramIndex.byLevel(this@CapabilityBroker, Level.APPLICATION).firstOrNull { it.id == id }
                ?: throw BridgeError(CODE_METHOD_NOT_FOUND, "无此实例: " + id)
            instanceJson(e)
        },
        "os.instances.action" to MethodDef(listOf("base"), true) { p, _programId ->
            val self = p.optString("id", "")
            val id = p.optString("id", self)
            val spec = lobos.os.ProgramRegistry.spec(this@CapabilityBroker, id)
                ?: throw BridgeError(CODE_METHOD_NOT_FOUND, "无此实例: " + id)
            val action = p.optString("action", "")
            if (action == "stop") {
                Journal.note(
                    this@CapabilityBroker, "instance", false, "实例动作被拒：停止是内核保留动作",
                    "id=" + id + "（内核尚未具备按程序独立停止的能力）",
                )
                throw BridgeError(CODE_POLICY_DENIED, "停止是内核保留动作，应用只能请求启动/重启")
            }
            val running = when (action) {
                "start" -> { toHost(InstanceHost.ACTION_START_RUNTIME); true }
                "restart" -> { toHost(InstanceHost.ACTION_RESTART); true }
                else -> throw BridgeError(CODE_INVALID_PARAM, "action 必须是 start|restart")
            }
            val desired = if (running) Desired.RUNNING else Desired.STOPPED
            val selfEntry = ProgramIndex.get(this@CapabilityBroker, self)
            if (selfEntry != null) {
                ProgramManager.setDesired(this@CapabilityBroker, self, desired)
            }
            Journal.append(this@CapabilityBroker, "instance", null, "os.instances.action=" + action + "（" + id + "）")
            JSONObject().apply {
                put("ok", true)
                put("desired", desired.name.lowercase(Locale.US))
                put("phase", desired.name.lowercase(Locale.US))
            }
        },
        "os.session.get" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply { put("sessionState", OsInit.current(this@CapabilityBroker).name.lowercase(Locale.US)) }
        },
        "os.session.stop" to MethodDef(listOf("base"), true) { _, _programId ->
            Journal.note(
                this@CapabilityBroker, "session", false, "会话停止被拒：停用运行时是内核保留动作",
                "应用无权请求宿主停机；宿主停机只能经内核内部路径",
            )
            throw BridgeError(CODE_POLICY_DENIED, "停用运行时是内核保留动作，应用无权调用")
        },
        "os.programs.overview" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("installed", ProgramIndex.byLevel(this@CapabilityBroker, Level.APPLICATION).size)
                put("programs", programsJson())
                put("versionInfo", JSONObject().apply {
                    val ids = lobos.os.ProgramRegistry.listIds(this@CapabilityBroker)
                    val single = ids.singleOrNull()?.let { ProgramDir(this@CapabilityBroker, it).currentVersion() }
                    put("current", single ?: "")
                    put("programs", JSONObject(ids.associateWith { ProgramDir(this@CapabilityBroker, it).currentVersion() ?: "" }))
                })
                put("upgrade", JSONObject().apply { put("updateAvailable", false) })
            }
        },
        "os.programs.list" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply { put("programs", programsJson()) }
        },
        "os.programs.settings" to MethodDef(listOf("base"), true) { p, _programId ->
            val id = p.optString("id", "")
            if (id.isBlank()) {
                throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id（内核没有\"主程序\"概念）")
            }
            val patch = JSONObject(p.toString())
            patch.remove("id")
            if (patch.length() == 0) throw BridgeError(CODE_INVALID_PARAM, "空补丁：至少给一个要写的键")
            val res = ProgramSettings.patch(this@CapabilityBroker, id, patch)
            Journal.note(this@CapabilityBroker, "programs", res.ok, "settings 更新: " + id)
            JSONObject().apply {
                put("ok", res.ok)
                put("settings", res.merged)
                put("rejected", org.json.JSONArray(res.rejected))
            }
        },

        "os.appmgr.install" to MethodDef(listOf("base"), true) { p, _programId -> startProgramJob("install", p.optString("id", "")) },
        "os.appmgr.upgrade" to MethodDef(listOf("base"), true) { p, _programId -> startProgramJob("upgrade", p.optString("id", "")) },
        "os.appmgr.uninstall" to MethodDef(listOf("base"), true) { p, _programId -> startUninstallJob(p.optString("id", "")) },
        "os.appmgr.checkUpdate" to MethodDef(listOf("base"), false) { p, _programId ->
            val target = p.optString("id", "")
            if (target.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：内核不接受\"默认程序\"")
            val out = ProgramOtaUpdater.checkAndUpdate(this@CapabilityBroker, ProgramDir(this@CapabilityBroker, target), checkOnly = true)
            JSONObject().apply {
                put("updateAvailable", out.available)
                put("latest", out.remote ?: JSONObject.NULL)
                put("current", out.current ?: JSONObject.NULL)
                put("checkedAt", System.currentTimeMillis())
                put("detail", out.detail)
            }
        },

        "os.registry.info" to MethodDef(listOf("base"), false) { _, _programId -> RegistryStore.info(this@CapabilityBroker) },
        "os.registry.apps" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply { put("programs", programsJson()) }
        },
        "os.registry.set" to MethodDef(listOf("base"), true) { p, _programId ->
            val origin = p.optString("origin", "").trim()
            if (origin.isBlank() || !origin.startsWith("https://")) {
                throw BridgeError(CODE_INVALID_PARAM, "origin 必须是非空 https:// URL（镜像源只走 TLS）")
            }
            RegistryStore.setOrigin(this@CapabilityBroker, origin, null)
        },
        "os.registry.refresh" to MethodDef(listOf("base"), true) { _, _programId ->
            val cur = RegistryStore.info(this@CapabilityBroker).optString("origin", "")
            if (cur.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "尚未设置镜像源（先 os.registry.set）")
            RegistryStore.setOrigin(this@CapabilityBroker, cur, RegistryStore.probe(cur))
        },
        "os.registry.probe" to MethodDef(listOf("base"), false) { p, _programId ->
            val origin = p.optString("origin", "").trim().ifBlank {
                RegistryStore.info(this@CapabilityBroker).optString("origin", "")
            }
            if (origin.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "未提供 origin 且尚未设置镜像源")
            RegistryStore.probe(origin)
        },
        "os.ports.list" to MethodDef(listOf("base"), false) { _, _programId ->
            val leases = PortBroker.list(this@CapabilityBroker)
            val arr = JSONArray()
            leases.forEach { l -> arr.put(JSONObject().apply { put("port", l.port); put("owner", l.owner) }) }
            JSONObject().apply {
                put("fixed", JSONArray()); put("user", arr)
                put("allocated", leases.size)
                put("capacity", PortBroker.RANGE_END - PortBroker.RANGE_START + 1)
            }
        },
        "os.ports.claim" to MethodDef(listOf("base"), true) { p, _programId ->
            val owner = p.optString("owner", "")
            val pref = if (p.has("preferred")) p.optInt("preferred") else null
            JSONObject().apply { put("port", PortBroker.claim(this@CapabilityBroker, owner, pref)); put("mode", "claimed") }
        },
        "os.ports.release" to MethodDef(listOf("base"), true) { p, _programId ->
            PortBroker.release(this@CapabilityBroker, p.optString("owner", ""))
            JSONObject().apply { put("ok", true) }
        },
        "os.runtime.status" to MethodDef(listOf("base"), false) { _, _programId ->
            val node = lobos.os.NodeRuntime.path(this@CapabilityBroker)
            val res = lobos.os.ResidencyStatus.snapshot()
            JSONObject().apply {
                put("name", "node")
                put("version", lobos.os.NodeRuntime.version(this@CapabilityBroker))
                put("path", node?.absolutePath ?: JSONObject.NULL)
                put("ok", node != null)
                put("detail", if (node == null) lobos.os.NodeRuntime.missing(this@CapabilityBroker) else JSONObject.NULL)
                put("degraded", res.optBoolean("degraded", false))
                put("degradedReasons", res.optJSONArray("degradedReasons") ?: JSONArray())
                put("actions", res.optJSONArray("actions") ?: JSONArray())
                put("accessibilityReady", res.optBoolean("accessibilityReady", false))
                put("adbReady", res.optBoolean("adbReady", false))
                put("programsRunning", res.optInt("programsRunning", 0))
                put("installedPrograms", res.optInt("installedPrograms", 0))
                put("runningIds", res.optJSONArray("runningIds") ?: JSONArray())
                put("tickGapMs", res.optLong("tickGapMs", 0L))
                put("frozen", res.optBoolean("frozen", false))
                put("uptimeMs", res.optLong("uptimeMs", 0L))
                put("residencyAt", res.optLong("updatedAt", 0L))
                put("tier", res.optString("tier", ""))
                put("tierBasis", res.optJSONArray("tierBasis") ?: JSONArray())
                put("adbState", res.optString("adbState", ""))
                put("adbAttempts", res.optInt("adbAttempts", 0))
                put("adbChannel", lobos.capability.AdbChannelComponent.status())
            }
        },

        "os.runtime.nodeLts" to MethodDef(listOf("base"), false) { _, _programId ->
            val cur = lobos.os.NodeRuntime.version(this@CapabilityBroker)
            val latest = runCatching {
                val arr = CatalogClient.entries(this@CapabilityBroker)
                var v = ""
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    if (t.optString("name", "") == lobos.os.NodeRuntime.NAME) {
                        v = t.optString("version", "")
                    }
                }
                v
            }.getOrDefault("")
            JSONObject().apply {
                put("current", cur)
                put("latest", latest)
                put("updateAvailable", latest.isNotBlank() && latest != cur)
            }
        },
        "os.compat.status" to MethodDef(listOf("base"), false) { _, _programId ->
            lobos.native.DriverRegistry.report(this@CapabilityBroker)
        },

        "os.env.status" to MethodDef(listOf("base"), false) { _, _programId ->
            val ev = CapabilityEvidenceCollector.systemReads(this@CapabilityBroker)
            val caps = BridgeTokens.from(ev)
            val verdicts = CapabilityCatalog.evaluate(ev)
            val tier = lobos.capability.CapabilityTier.fromEvidence(
                ev, lobos.capability.DeviceOwnerProbe.measure(this@CapabilityBroker),
            )
            JSONObject().apply {
                put("platform", "android")
                put("apiLevel", Build.VERSION.SDK_INT)
                put("tier", tier.toJson())
                put("capabilities", JSONArray().apply { caps.sorted().forEach { put(it) } })
                put("catalog", JSONArray().apply {
                    verdicts.keys.sorted().forEach { k ->
                        put(JSONObject().apply {
                            put("id", k)
                            put("status", verdicts[k]?.status?.name?.lowercase(Locale.US) ?: "unknown")
                        })
                    }
                })
            }
        },
        "os.env.programs" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply { put("programs", programsJson()) }
        },
        "os.manifest.spec" to MethodDef(listOf("base"), false) { _, _programId ->
            lobos.os.ManifestSchema.spec().apply {
                put("restarts", JSONArray(lobos.os.ManifestSchema.RESTARTS.toList()))
                put("uiTypes", JSONArray(lobos.os.ManifestSchema.UI_TYPES.toList()))
                put("onUiClosed", JSONArray(lobos.os.ManifestSchema.ON_CLOSED.toList()))
                put("restartAliases", JSONObject().apply {
                    for ((k, v) in lobos.os.ManifestSchema.RESTART_ALIASES) put(k, v)
                })
            }
        },
        "os.manifest.validate" to MethodDef(listOf("base"), false) { p, _programId ->
            val id = p.optString("id", "")
            if (id.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定 id")
            val spec = lobos.os.ProgramRegistry.spec(this@CapabilityBroker, id)
            if (spec == null) {
                JSONObject().apply {
                    put("id", id)
                    put("present", false)
                    put("valid", false)
                    put("errors", JSONArray(listOf("程序未安装")))
                }
            } else {
                val json = ProgramManager.dirOf(this@CapabilityBroker, id)
                    .rawManifest(spec.version) ?: JSONObject()
                lobos.os.ManifestSchema.toJson(json).apply {
                    put("id", id)
                    put("present", true)
                    put("version", spec.version)
                    if (spec.invalid != null && !has("errors")) {
                        put("valid", false)
                        put("errors", JSONArray(listOf(spec.invalid)))
                    }
                }
            }
        },
        "os.nativeAssets.status" to MethodDef(listOf("base"), false) { _, _programId ->
            val landed = RuntimeDiagnostics.latestByStage(
                this@CapabilityBroker, "native-assets", "capability-assets", "prefix",
            )
            JSONObject().apply {
                put("collected", landed.isNotEmpty())
                put("rounds", JSONArray().apply {
                    landed.forEach { (stage, ev) ->
                        put(JSONObject().apply {
                            put("stage", stage)
                            put("at", ev.atMs)
                            put("level", ev.level.name.lowercase(Locale.US))
                            put("message", ev.message)
                            ev.data?.let { put("report", it) }
                        })
                    }
                })
            }
        },
        "os.facilities.list" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("root", ProgramManager.stateRoot(this@CapabilityBroker).absolutePath)
                put("enabled", JSONArray(ProgramIndex.all(this@CapabilityBroker).filter { it.enabled }.map { it.id }.sorted()))
                put("facilities", ProgramManager.status(this@CapabilityBroker))
            }
        },
        "os.facilities.action" to MethodDef(listOf("base"), true) { p, _programId -> packagesAction(p) },
        "os.catalog.list" to MethodDef(listOf("base"), false) { p, _programId ->
            if (p.optBoolean("refresh", false)) CatalogClient.refresh(this@CapabilityBroker, false)
            CatalogClient.list(this@CapabilityBroker)
        },
        "os.catalog.refresh" to MethodDef(listOf("base"), true) { p, _programId ->
            CatalogClient.refresh(this@CapabilityBroker, p.optBoolean("force", true))
        },
        "os.packages.list" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("installed", ProgramManager.status(this@CapabilityBroker))
                put("catalog", CatalogClient.list(this@CapabilityBroker))
            }
        },
        "os.packages.action" to MethodDef(listOf("base"), true) { p, _programId -> packagesAction(p) },
        "os.diagnostics.events" to MethodDef(listOf("base"), false) { p, _programId ->
            val stage = p.optString("stage", "").takeIf { it.isNotBlank() }
            val level = p.optString("level", "").takeIf { it.isNotBlank() }?.uppercase(Locale.US)
            val limit = p.optInt("limit", 200).coerceIn(1, 2000)
            val window = RuntimeDiagnostics.events(this@CapabilityBroker, limit)
            val matched = window.filter { ev ->
                (stage == null || ev.stage.startsWith(stage)) && (level == null || ev.level.name == level)
            }
            JSONObject().apply {
                put("collected", window.isNotEmpty())
                put("total", window.size)
                put("matched", matched.size)
                put("events", JSONArray().apply { matched.forEach { put(it.toJson()) } })
            }
        },
        "os.provisioning.get" to MethodDef(listOf("base"), false) { _, _programId ->
            val snap = ProvisioningProbe.snapshot(this@CapabilityBroker)
            JSONObject().apply {
                put("present", snap != null)
                put("snapshot", snap ?: JSONObject.NULL)
            }
        },
        "capability.invoke" to MethodDef(listOf("base"), true) { p, programId ->
            val action = p.optString("action", "query").lowercase()
            val ctx = this@CapabilityBroker
            val evidence = runCatching { lobos.capability.CapabilityEvidenceCollector.systemReads(ctx) }
                .getOrElse { lobos.capability.Evidence() }
            when (action) {
                "query" -> JSONObject().apply {
                    put("ok", true)
                    put("program", programId)
                    put("held", lobos.capability.BridgeTokens.from(evidence).toList().sorted())
                    put("catalog", JSONArray().apply {
                        for (c in lobos.capability.CapabilityCatalog.ALL) {
                            val verdict = runCatching { lobos.capability.CapabilityCatalog.rawJudge(c.id, evidence) }.getOrNull()
                            put(JSONObject().apply {
                                put("id", c.id)
                                put("title", c.title)
                                put("status", verdict?.status?.name ?: "UNKNOWN")
                                put("detail", verdict?.detail ?: "")
                                put("token", c.bridgeToken ?: JSONObject.NULL)
                            })
                        }
                    })
                }
                "refresh" -> JSONObject().apply {
                    val fresh = runCatching { lobos.capability.CapabilityEvidenceCollector.collect(ctx) }
                        .getOrElse { lobos.capability.Evidence() }
                    put("ok", true)
                    put("program", programId)
                    put("held", lobos.capability.BridgeTokens.from(fresh).toList().sorted())
                }
                else -> throw BridgeError(CODE_INVALID_PARAM, "action 必须是 query|refresh")
            }
        },
    )
    private val METHODS: Map<String, MethodDef> = mapOf(
        "sys.info" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidApi", Build.VERSION.SDK_INT)
                put("platform", "android")
                put("bridge", SOCKET_NAME)
            }
        },
        "os.permissions.ledger" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { p, _programId ->
            val snap = lobos.permissions.PermissionLedger.register(this@CapabilityBroker)
            lobos.permissions.PermissionLedger.toJson(snap)
        },
        "os.permissions.roles" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
            JSONObject().apply {
                put("roles", JSONArray().apply {
                    for (r in lobos.permissions.PermissionRoles.declared()) {
                        put(JSONObject().apply {
                            put("id", r.id)
                            put("purpose", r.purpose)
                            put("policy", r.policy.name)
                            put("autoHeal", r.autoHeal.name)
                            put("owner", r.owner)
                        })
                    }
                })
                put("undeclared", JSONArray().apply {
                    for (r in lobos.permissions.PermissionRoles.undeclared()) put(r.id)
                })
            }
        },
        "os.notif.publish" to MethodDef(listOf("base"), true) { p, programId ->
            if (programId.isBlank()) {
                throw BridgeError(CODE_CAPABILITY_MISSING, "只有程序会话才能投递通知")
            }
            val n = lobos.os.ProgramNotificationHub.publish(
                this@CapabilityBroker,
                programId,
                p.optString("title", ""),
                p.optString("text", ""),
                p.optBoolean("ongoing", true),
                p.optBoolean("silent", true),
                p.optString("group", "").ifBlank { null },
                p.optString("groupLabel", "").ifBlank { null },
                p.optString("subText", "").ifBlank { null },
                if (p.has("progress")) p.optInt("progress", -1) else null,
            )
            JSONObject().apply {
                put("ok", true)
                put("programId", n.programId)
                put("atMs", n.atMs)
            }
        },
        "os.notif.clear" to MethodDef(listOf("base"), true) { p, programId ->
            val target = p.optString("programId", "").ifBlank { programId }
            if (target != programId) {
                throw BridgeError(CODE_POLICY_DENIED, "只能撤下自己的通知")
            }
            JSONObject().apply {
                put("ok", true)
                put("cleared", lobos.os.ProgramNotificationHub.clear(this@CapabilityBroker, target))
            }
        },
        "os.notif.list" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("notices", lobos.os.ProgramNotificationHub.listJson())
            }
        },
        "os.host.status" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
            lobos.os.ProgramStatusHub.toJson(this@CapabilityBroker).apply {
                put("notices", lobos.os.ProgramNotificationHub.listJson())
                put("noticeGroups", lobos.os.ProgramNotificationHub.groupsJson())
                put("writeFailures", lobos.os.StateFiles.writeFailureCount(this@CapabilityBroker))
            }
        },
        "os.accessibility.state" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
            JSONObject().apply {
                put("state", AccessibilityServiceState.state(this@CapabilityBroker).name)
                put("connected", AccessibilityServiceState.isBound(this@CapabilityBroker))
                put("role", "UI 自动化的执行体（不参与保活）")
            }
        },
        "os.accessibility.enable" to MethodDef(listOf(ApiSpec.GROUP_SYS), true) { p, _programId ->
            val ms = p.optLong("timeoutMs", 8000L)
            val out = AccessibilityServiceState.ensureBound(this@CapabilityBroker, ms)
            JSONObject().apply {
                put("state", out.state.name)
                put("connected", out.bound)
                put("issued", out.issued)
                put("detail", out.detail)
            }
        },
        "os.accessibility.disable" to MethodDef(listOf(ApiSpec.GROUP_SYS), true) { _, _programId ->
            val out = AccessibilityServiceState.disable(this@CapabilityBroker)
            JSONObject().apply {
                put("state", out.state.name)
                put("connected", out.bound)
                put("issued", out.issued)
                put("detail", out.detail)
            }
        },
        "os.accessibility.actions" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
            val a11y = requireA11y()
            JSONObject().apply {
                put("globalActions", org.json.JSONArray(a11y.globalActions()))
                put("eventTypes", org.json.JSONArray(a11y.eventTypeNames()))
            }
        },
        "sys.api" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
            ApiSpec.toJson((METHODS.keys + OS_METHODS.keys).distinct().sorted()).apply {
                put("protocol", JSONObject().apply {
                    put("min", PROTOCOL_MIN)
                    put("max", BuildConfig.BRIDGE_PROTOCOL)
                })
                put("summary", ApiSpec.surface())
                put("aliases", JSONArray().apply {
                    for (m in (METHODS.keys + OS_METHODS.keys).distinct().sorted().filter { ApiSpec.isDeprecated(it) }) {
                        put(JSONObject().apply { put("alias", m); put("canonical", ApiSpec.canonical(m)) })
                    }
                })
            }
        },
        "sys.nativeAssets" to MethodDef(listOf("base"), false) { p, _programId ->
            val walkProbes = p.optBoolean("walkProbes", true)
            val report = if (walkProbes) {
                NativePreparer.prepare(this)
            } else {
                PrepareReport(NativeAssetRegistry.ALL.map { exe -> exe to NativePreparer.verify(this, exe) })
            }
            report.toJson().apply {
                put("nativeLibraryDir", applicationInfo.nativeLibraryDir)
                put("libSearchPath", NativePreparer.libSearchPath(this@CapabilityBroker))
            }
        },
        "notif.post" to MethodDef(listOf("base"), true) { p, _programId ->
            val ch = "hostbridge_notif"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notifManager.createNotificationChannel(
                    NotificationChannel(ch, "HostBridge", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(this, ch)
            else android.app.Notification.Builder(this)
            n.setContentTitle(p.optString("title", "Lob OS")).setContentText(p.optString("text", ""))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
            notifManager.notify((10000 + (System.currentTimeMillis() % 55000)).toInt(), n.build())
            JSONObject().apply { put("posted", true) }
        },
        "notif.read" to MethodDef(listOf("notification_access"), true) { p, _programId ->
            if (!NotificationStore.connected) {
                throw BridgeError(
                    CODE_CAPABILITY_MISSING,
                    "通知监听未连接：请在 设置 → 通知 → 通知使用权 中启用本应用后重试。"
                )
            }
            val limit = p.optInt("limit", 50).coerceIn(1, 200)
            val arr = NotificationStore.snapshot(limit)
            JSONObject().apply {
                put("notifications", arr)
                put("count", arr.length())
            }
        },
        "app.listInstalled" to MethodDef(listOf("base"), false) { _, _programId ->
            val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val arr = JSONArray()
            for (ai in apps) arr.put(ai.packageName)
            JSONObject().apply { put("packages", arr); put("count", arr.length()) }
        },
        "app.launch" to MethodDef(listOf("base"), false) { p, _programId ->
            val pkg = p.optString("pkg", "")
            val ai = packageManager.getLaunchIntentForPackage(pkg)
            if (ai == null) throw BridgeError(CODE_INVALID_PARAM, "无启动入口: $pkg")
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(ai)
            JSONObject().apply { put("launched", pkg) }
        },
        "app.openUrl" to MethodDef(listOf("base"), false) { p, _programId ->
            val url = p.optString("url", "")
            if (url.isEmpty()) throw BridgeError(CODE_INVALID_PARAM, "url 为空")
            val ai = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            ai.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ai.resolveActivity(packageManager) == null) throw BridgeError(CODE_INVALID_PARAM, "无可用浏览器: $url")
            startActivity(ai)
            JSONObject().apply { put("opened", true); put("url", url) }
        },
        "ui.tap" to MethodDef(listOf("accessibility"), true) { p, _programId ->
            val a11y = requireA11y()
            val ok = a11y.performTap(
                p.optDouble("x", 0.0).toFloat(),
                p.optDouble("y", 0.0).toFloat(),
                p.optLong("durationMs", 60L)
            )
            JSONObject().apply { put("ok", ok) }
        },
        "ui.swipe" to MethodDef(listOf("accessibility"), true) { p, _programId ->
            val a11y = requireA11y()
            val ok = a11y.performSwipe(
                p.optDouble("x1", 0.0).toFloat(), p.optDouble("y1", 0.0).toFloat(),
                p.optDouble("x2", 0.0).toFloat(), p.optDouble("y2", 0.0).toFloat(),
                p.optLong("durationMs", 300L)
            )
            JSONObject().apply { put("ok", ok) }
        },
        "ui.inputText" to MethodDef(listOf("accessibility"), true) { p, _programId ->
            val a11y = requireA11y()
            val text = p.optString("text", "")
            if (p.has("selector") && p.optJSONObject("selector")?.length() == 0) {
                throw BridgeError(CODE_INVALID_PARAM, "selector 不能为空对象")
            }
            val ok = a11y.inputText(text, p.optJSONObject("selector"))
            JSONObject().apply { put("ok", ok) }
        },
        "ui.getUiTree" to MethodDef(listOf("accessibility"), false) { p, _programId ->
            val a11y = requireA11y()
            a11y.dumpUiTree(
                maxNodes = p.optInt("maxNodes", 3000),
                maxDepth = p.optInt("maxDepth", 40)
            )
        },
        "ui.globalAction" to MethodDef(listOf("accessibility"), true) { p, _programId ->
            val a11y = requireA11y()
            val action = p.optString("action", "")
            val id = a11y.globalActionOf(action)
            if (id < 0) {
                throw BridgeError(
                    CODE_INVALID_PARAM,
                    "不支持的全局动作：" + action + "（可用：" + a11y.globalActions().joinToString(",") + "）",
                )
            }
            JSONObject().apply {
                put("ok", a11y.performGlobalAction(action))
                put("action", action)
            }
        },
        "ui.events" to MethodDef(listOf("accessibility"), false) { p, _programId ->
            val a11y = requireA11y()
            val names = p.optJSONArray("types")
            val types = names?.let { a ->
                (0 until a.length()).map { a11y.eventTypeOf(a.optString(it)) }.filter { it >= 0 }
            } ?: emptyList()
            JSONObject().apply {
                put("seq", a11y.eventSeq())
                put("uiSeq", a11y.uiSeq())
                put("windowDirty", a11y.takeWindowDirty())
                put("buffered", a11y.eventCount())
                put("typeNames", org.json.JSONArray(a11y.eventTypeNames()))
                put("events", a11y.eventsSince(
                    p.optLong("since", 0L),
                    p.optInt("max", 100),
                    types,
                ))
            }
        },
        "ui.dropEvents" to MethodDef(listOf("accessibility"), true) { p, _programId ->
            val a11y = requireA11y()
            JSONObject().apply {
                put("dropped", a11y.dropEventsBefore(p.optLong("seq", 0L)))
                put("seq", a11y.eventSeq())
            }
        },
        "ui.screenshot" to MethodDef(listOf("mediaprojection"), true) { p, _programId ->
            val svc = ScreenCaptureController.instance
                ?: throw BridgeError(
                    CODE_CAPABILITY_MISSING,
                    "截屏服务未启动。需先在 App 内完成一次截屏授权（系统弹窗），此后可后台复用"
                )
            val metrics = resources.displayMetrics
            val w = p.optInt("width", metrics.widthPixels)
            val h = p.optInt("height", metrics.heightPixels)
            val bmp = svc.capture(w, h, metrics.densityDpi)
                ?: throw BridgeError(CODE_INTERNAL, "截屏失败（8s 内未取到帧；可能屏幕处于锁屏/息屏）")

            val dir = File(filesDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val outW = bmp.width
            val outH = bmp.height
            bmp.recycle()

            if (p.optBoolean("inline", false)) {
                val bytes = file.readBytes()
                JSONObject().apply {
                    put("encoding", "base64")
                    put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    put("width", outW); put("height", outH); put("bytes", bytes.size)
                    put("path", file.absolutePath)
                }
            } else {
                JSONObject().apply {
                    put("path", file.absolutePath)
                    put("width", outW)
                    put("height", outH)
                    put("bytes", file.length())
                }
            }
        },
        "ui.waitFor" to MethodDef(listOf("accessibility"), false) { p, _programId ->
            val a11y = requireA11y()
            val selector = p.optJSONObject("selector")
                ?: throw BridgeError(CODE_INVALID_PARAM, "waitFor 需要 selector")
            a11y.waitForNode(
                selector,
                timeoutMs = p.optLong("timeoutMs", 10_000L),
                intervalMs = p.optLong("intervalMs", 250L)
            )
        },
        "shell.status" to MethodDef(listOf("base"), false) { _, _programId ->
            val res = AdbClientRunner.status(this)
            if (!res.ok) throw BridgeError(CODE_INTERNAL, "ADB status 失败: ${res.error ?: res.raw.take(300)}")
            res.json ?: JSONObject()
        },
        "shell.pair" to MethodDef(listOf("base"), true) { p, _programId ->
            val host = p.optString("host", "")
            val pairPort = p.optInt("pairPort", 0)
            val code = p.optString("code", "")
            if (host.isBlank() || pairPort <= 0 || code.isBlank()) {
                throw BridgeError(CODE_INVALID_PARAM, "pair 需要 host / pairPort / code")
            }
            val connectPort = if (p.has("connectPort")) p.optInt("connectPort", 0).takeIf { it > 0 } else null
            val timeoutMs = p.optLong("timeoutMs", 30_000L).coerceIn(1_000L, 120_000L)
            val res = AdbClientRunner.pair(this, host, pairPort, code, connectPort, timeoutMs)
            if (!res.ok) throw BridgeError(CODE_INTERNAL, "配对失败: ${res.error ?: res.raw.take(300)}")
            res.json ?: JSONObject()
        },
        "shell.forget" to MethodDef(listOf("base"), true) { _, _programId ->
            val res = AdbClientRunner.forget(this)
            if (!res.ok) throw BridgeError(CODE_INTERNAL, "forget 失败: ${res.error ?: res.raw.take(300)}")
            JSONObject().apply { put("ok", true) }
        },
        "shell.exec" to MethodDef(listOf("adb_shell"), true) { p, _programId ->
            val cmd = p.optString("cmd", "")
            if (cmd.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "cmd 为空")
            val arr = p.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList()
            val timeoutMs = p.optLong("timeoutMs", 10_000L).coerceIn(1L, 60_000L)
            val full = if (arr.isEmpty()) cmd
                       else cmd + " " + arr.joinToString(" ") { shellQuote(it) }
            val res = AdbClientRunner.shell(this, full, null, null, timeoutMs)
            if (!res.ok) throw BridgeError(CODE_INTERNAL, "ADB shell 失败: ${res.error ?: res.raw.take(300)}")
            val outStr = res.json?.optString("out", "") ?: ""
            JSONObject().apply {
                put("ok", true)
                put(
                    "stdout",
                    if (outStr.length > MAX_SHELL_OUTPUT) outStr.take(MAX_SHELL_OUTPUT) + "\n…(截断)"
                    else outStr
                )
                put("uid", 2000)
                put("privileged", true)
                put("note", "以 shell uid(2000) 经内置 ADB 客户端（无线调试）执行。")
            }
        },
        "fs.read" to MethodDef(listOf("manage_external_storage"), false) { p, _programId ->
            lobos.os.PathGuard.rejection(this@CapabilityBroker, p.optString("path", ""))?.let {
                throw BridgeError(CODE_POLICY_DENIED, it)
            }
            val f = requireReadableFile(p.optString("path", ""))
            val maxBytes = p.optLong("maxBytes", DEFAULT_FS_MAX_BYTES).coerceIn(1L, MAX_FS_BYTES)
            if (f.length() > maxBytes) {
                throw BridgeError(
                    CODE_INVALID_PARAM,
                    "文件 ${f.length()} 字节超过上限 $maxBytes；用 maxBytes 显式放大（硬顶 ${MAX_FS_BYTES}）"
                )
            }
            val bytes = f.readBytes()
            val encoding = p.optString("encoding", "auto")
            if (encoding == "base64") {
                JSONObject().apply {
                    put("path", f.absolutePath)
                    put("bytes", bytes.size)
                    put("encoding", "base64")
                    put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
            } else {
                val text = String(bytes, Charsets.UTF_8)
                val lossless = text.toByteArray(Charsets.UTF_8).contentEquals(bytes)
                JSONObject().apply {
                    put("path", f.absolutePath)
                    put("bytes", bytes.size)
                    if (lossless) {
                        put("encoding", "utf8")
                        put("content", text)
                    } else {
                        put("encoding", "base64")
                        put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        put("note", "内容非合法 UTF-8，已自动以 base64 返回（避免损坏二进制）")
                    }
                }
            }
        },
        "fs.write" to MethodDef(listOf("manage_external_storage"), true) { p, _programId ->
            lobos.os.PathGuard.rejection(this@CapabilityBroker, p.optString("path", ""))?.let {
                throw BridgeError(CODE_POLICY_DENIED, it)
            }
            val f = requireWritableFile(p.optString("path", ""))
            val encoding = p.optString("encoding", "utf8")
            val content = p.optString("content", "")
            val bytes = if (encoding == "base64") {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } else {
                content.toByteArray(Charsets.UTF_8)
            }
            val append = p.optBoolean("append", false)
            f.parentFile?.mkdirs()
            if (append) f.appendBytes(bytes) else f.writeBytes(bytes)
            JSONObject().apply {
                put("path", f.absolutePath)
                put("bytes", bytes.size)
                put("appended", append)
                auditPathHint(f.absolutePath)?.let { put("hint", it) }
            }
        },
        "fs.list" to MethodDef(listOf("manage_external_storage"), false) { p, _programId ->
            lobos.os.PathGuard.rejection(this@CapabilityBroker, p.optString("path", ""))?.let {
                throw BridgeError(CODE_POLICY_DENIED, it)
            }
            val path = p.optString("path", "")
            val f = when {
                path.isBlank() -> File(Environment.getExternalStorageDirectory().absolutePath)
                else -> File(path)
            }
            if (!f.exists()) throw BridgeError(CODE_INVALID_PARAM, "路径不存在: ${f.absolutePath}")
            val recursive = p.optBoolean("recursive", false)
            val maxEntries = p.optInt("maxEntries", 1000).coerceIn(1, 10000)
            val arr = JSONArray()
            var truncated = false
            if (f.isDirectory) {
                if (recursive) f.walkTopDown().forEach { c ->
                    if (arr.length() >= maxEntries) { truncated = true; return@forEach }
                    if (c.absolutePath != f.absolutePath) arr.put(fileToJson(c))
                } else {
                    val kids = f.listFiles() ?: emptyArray()
                    for (c in kids) {
                        if (arr.length() >= maxEntries) { truncated = true; break }
                        arr.put(fileToJson(c))
                    }
                }
            }
            JSONObject().apply {
                put("path", f.absolutePath)
                put("isDirectory", f.isDirectory)
                put("entries", arr)
                put("count", arr.length())
                put("truncated", truncated)
            }
        },
        "fs.mkdir" to MethodDef(listOf("manage_external_storage"), true) { p, _programId ->
            lobos.os.PathGuard.rejection(this@CapabilityBroker, p.optString("path", ""))?.let {
                throw BridgeError(CODE_POLICY_DENIED, it)
            }
            val f = requireWritableFile(p.optString("path", ""))
            val ok = if (f.exists()) f.isDirectory else f.mkdirs()
            if (!ok) throw BridgeError(CODE_INTERNAL, "创建目录失败: ${f.absolutePath}")
            JSONObject().apply {
                put("path", f.absolutePath)
                put("existed", f.exists())
            }
        },
        "build.programInstall" to MethodDef(listOf("program_update"), true) { p, _programId ->
            val checkOnly = p.optBoolean("checkOnly", false)
            val target = p.optString("id", "")
            if (target.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：内核不接受\"默认程序\"")
            val ota = ProgramOtaUpdater.checkAndUpdate(this, ProgramDir(this, target), checkOnly)
            JSONObject().apply {
                put("ok", if (checkOnly) ota.checked else ota.updated)
                put("checked", ota.checked)
                put("available", ota.available)
                put("updated", ota.updated)
                put("current", ota.current ?: JSONObject.NULL)
                put("version", ota.remote ?: JSONObject.NULL)
                put("source", ProgramInstaller.Source.OTA.label)
                put("detail", ota.detail)
                put("restartRequired", ota.updated)
            }
        },
        "build.programStatus" to MethodDef(listOf("program_update"), false) { _, _programId ->
            val ids = lobos.os.ProgramRegistry.listIds(this)
            val single = ids.singleOrNull()?.let { ProgramDir(this@CapabilityBroker, it) }
            JSONObject().apply {
                put("current", single?.currentVersion() ?: JSONObject.NULL)
                put("programs", JSONObject(ids.associateWith { ProgramDir(this@CapabilityBroker, it).currentVersion() ?: "" }))
                put("installed", JSONArray(ids.flatMap { ProgramDir(this@CapabilityBroker, it).installedVersions() }))
                put("integrity", JSONArray(ids.flatMap { ProgramDir(this@CapabilityBroker, it).integrityChecks() }))
            }
        },
        "build.apk" to MethodDef(listOf("program_update"), true) { p, _programId ->
            throw BridgeError(
                CODE_INVALID_PARAM,
                "build.apk 已废弃：内置构建链经实测不可行（Google Maven 无 aarch64 版 aapt2，" +
                    "interp/架构/libc 三关装机后无法补救）。请改用 build.programInstall —— " +
                    "设备安装已签名内核，无需编译。"
            )
        },
        "build.status" to MethodDef(listOf("program_update"), false) { _, _programId ->
            val ids = lobos.os.ProgramRegistry.listIds(this)
            val single = ids.singleOrNull()?.let { ProgramDir(this@CapabilityBroker, it) }
            JSONObject().apply {
                put("current", single?.currentVersion() ?: JSONObject.NULL)
                put("programs", JSONObject(ids.associateWith { ProgramDir(this@CapabilityBroker, it).currentVersion() ?: "" }))
                put("installed", JSONArray(ids.flatMap { ProgramDir(this@CapabilityBroker, it).installedVersions() }))
            }
        }
    )

    private fun fileToJson(f: File): JSONObject = JSONObject().apply {
        put("name", f.name)
        put("path", f.absolutePath)
        put("directory", f.isDirectory)
        put("size", if (f.isDirectory) 0L else f.length())
        put("modified", f.lastModified())
        put("readable", f.canRead())
        put("writable", f.canWrite())
    }

    private fun requireReadableFile(path: String): File {
        if (path.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "path 为空")
        val f = File(path)
        if (!f.exists()) throw BridgeError(CODE_INVALID_PARAM, "文件不存在: $path")
        if (f.isDirectory) throw BridgeError(CODE_INVALID_PARAM, "是目录而非文件: $path")
        if (!f.canRead()) throw BridgeError(CODE_INVALID_PARAM, "无读权限: $path")
        return f
    }

    private fun requireWritableFile(path: String): File {
        if (path.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "path 为空")
        val f = File(path)
        if (f.exists() && f.isDirectory) throw BridgeError(CODE_INVALID_PARAM, "是目录而非文件: $path")
        val parent = f.parentFile
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw BridgeError(CODE_INVALID_PARAM, "无法创建父目录: ${parent.absolutePath}")
            }
        }
        if (parent != null && !parent.canWrite()) {
            throw BridgeError(CODE_INVALID_PARAM, "父目录不可写: ${parent.absolutePath}")
        }
        return f
    }

    private fun auditPathHint(path: String): String? {
        val p = path.trim()
        return when {
            p.startsWith("/dev/") || p == "/dev" ->
                "⚠ 写入 /dev 下的块设备/字符设备可能立即损坏设备数据"
            p.startsWith("/proc/") || p.startsWith("/sys/") ->
                "⚠ /proc 与 /sys 是内核接口，写入可能使系统立即不稳定或崩溃"
            p.startsWith("/system") || p.startsWith("/vendor") || p.startsWith("/boot") ->
                "⚠ 系统分区受 verified boot 保护，写入通常失败；强行修改可能导致设备无法启动"
            p == "/" -> "⚠ 根目录写入：请确认目标路径"
            else -> null
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun shutdown() {
        running = false
        for ((_, s) in servers) runCatching { s.close() }
        servers.clear()
        live = null
    }

    fun invokeLocal(programId: String, method: String, params: JSONObject): JSONObject {
        if (live == null) return localFail("宿主桥未启动：快应用能力面此刻不可用")
        val id = programId.trim()
        if (id.isBlank()) return localFail("缺少 programId")
        if (!lobos.os.ProgramRegistry.listIds(this).contains(id)) {
            return localFail("程序不在册，不得发起能力调用: $id")
        }
        val session = lobos.os.SessionRegistry.issue(this, id, 0L)
        val holder = SessionHolder(lobos.os.SessionRegistry.socketName(session.token))
        holder.session = session
        holder.granted = serverGranted(session).toSet()
        holder.system = holder.granted.contains(ApiSpec.GROUP_SYS)
        val req = JSONObject().apply {
            put("id", 0)
            put("method", method)
            put("params", params ?: JSONObject())
        }
        val res = dispatch(req, holder) ?: return localFail("宿主桥无响应")
        val err = res.optJSONObject("error")
        if (err != null) {
            return JSONObject().apply {
                put("ok", false)
                put("code", err.optInt("code", CODE_INTERNAL))
                put("error", err.optString("message", ""))
            }
        }
        return res.optJSONObject("result")?.apply { if (!has("ok")) put("ok", true) }
            ?: JSONObject().apply { put("ok", true) }
    }

    private fun localFail(message: String): JSONObject =
        JSONObject().apply { put("ok", false); put("code", CODE_SESSION_MISSING); put("error", message) }

    companion object {
        const val TAG = "CapabilityBroker"
        const val SOCKET_NAME = GuestAdapter.BRIDGE_SOCKET
        const val MAX_FRAME_CHARS = 256 * 1024
        const val MAX_CONNECTIONS = 16

        @Volatile private var live: CapabilityBroker? = null

        fun live(): CapabilityBroker? = live

        fun prepareSession(ctx: Context, programId: String, generation: Long): String {
            val b = live
            return if (b != null) b.beginSession(programId, generation)
            else lobos.os.SessionRegistry.issue(ctx, programId, generation)
        }
        const val CODE_CAPABILITY_MISSING = -32001
        const val CODE_INVALID_PARAM = -32602
        const val CODE_METHOD_NOT_FOUND = -32601
        const val CODE_NOT_IMPLEMENTED = -32002
        const val CODE_SESSION_MISSING = -32004
        const val CODE_PROTOCOL_UNSUPPORTED = -32006
        const val PROTOCOL_MIN = 1
        const val CODE_POLICY_DENIED = -32005
        const val CODE_INTERNAL = -32603

        const val EXTRA_PKG = "lobos_target"

        const val MAX_SHELL_OUTPUT = 256 * 1024

        const val DEFAULT_FS_MAX_BYTES = 8L * 1024 * 1024
        const val MAX_FS_BYTES = 64L * 1024 * 1024


    }
}

data class MethodDef(
    val caps: List<String>,
    val audit: Boolean,
    val handle: (JSONObject, String) -> JSONObject
)

class BridgeError(val code: Int, message: String) : Exception(message)

private fun JSONArray.toList(): List<String> {
    val out = mutableListOf<String>()
    for (i in 0 until length()) out.add(getString(i))
    return out
}
