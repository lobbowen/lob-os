package lobos.api

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
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import lobos.BuildConfig
import lobos.R
import lobos.services.log.RuntimeDiagnostics
import lobos.device.DeviceOwnerState
import lobos.device.ScreenCaptureController
import lobos.device.AccessibilityServiceState
import lobos.device.OsAccessibilityService
import lobos.services.host.OsHostService
import lobos.services.log.Journal
import lobos.services.supply.CatalogClient
import lobos.services.reg.Desired
import lobos.services.reg.Level
import lobos.services.host.OsInit
import lobos.services.host.OsPhase
import lobos.services.supply.PackageInstaller
import lobos.kernel.security.PathGuard
import lobos.services.reg.PortBroker
import lobos.services.supply.ProgramDir
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramManager
import lobos.services.reg.ProgramSettings
import lobos.services.supply.RegistryStore
import lobos.kernel.proc.SessionRegistry
import lobos.kernel.fs.StateFiles
import lobos.services.supervise.TaskRegistry
import lobos.services.reg.UnitEntry
import lobos.services.supply.ProgramInstaller
import lobos.services.supply.ProgramOtaUpdater
import lobos.device.NotificationStore
import lobos.services.perm.PermissionLedger
import lobos.services.perm.PermissionRoles
import lobos.services.app.DriverRegistry
import lobos.services.app.PieceProvisioner
import lobos.services.app.PrepareReport
import lobos.services.supervise.GuestAdapter
import lobos.services.supervise.InstanceHost
import lobos.kernel.ipc.LocalExec
import lobos.services.supply.PieceUpdater
import lobos.services.supply.ShellEntry
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.mm.LibPath

class CapabilityBroker(private val host: Service) : ContextWrapper(host) {

    private val servers = java.util.concurrent.ConcurrentHashMap<String, LocalServerSocket>()
    private var running = false
    private var socketName: String = GuestAdapter.BRIDGE_SOCKET
    @Volatile private var activeConnections = 0
    @Volatile private var installerToken: String = ""
    private val executor = Executors.newCachedThreadPool()
    private lateinit var notifManager: NotificationManager

    fun start() {
        notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        live = this
        startBridge()
        issueInstallerSession()
        OsHostService.ensureRunning(this)
    }

    fun issueInstallerSession() {
        if (installerToken.isNotBlank()) return
        runCatching { beginSession(INSTALLER_ID, INSTALLER_GENERATION) }
            .onSuccess { installerToken = it }
            .onFailure { Log.w(TAG, "安装会话签发失败（控制面装不了第一个程序）", it) }
    }

    fun installerToken(): String = installerToken

    private fun onListenFailed(name: String, e: Throwable) {
        val addrInUse = e is java.net.BindException && (e.message ?: "").contains("Address already in use")
        if (addrInUse && socketPresent(name)) {
            RuntimeDiagnostics.append(
                this, "bridge", true, "HostBridge 监听沿用既有 socket",
                "name=" + name + "（上次进程退出时未释放，本进程不重复绑）",
            )
            return
        }
        RuntimeDiagnostics.append(
            this, "bridge", false, "HostBridge 监听失败",
            "name=" + name + " " + e::class.java.simpleName + ": " + e.message,
        )
    }

    private fun socketPresent(name: String): Boolean = runCatching {
        LocalSocket().use { s -> s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
        true
    }.getOrDefault(false)

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
                onListenFailed(name, e)
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
        val token = lobos.kernel.proc.SessionRegistry.issue(this, programId, generation)
        val name = lobos.kernel.proc.SessionRegistry.socketName(token)
        if (running && !servers.containsKey(name)) listenLoop(name)
        return token
    }

    private fun sessionOfSocket(socketName: String): lobos.kernel.proc.SessionRegistry.Session? {
        val s = lobos.kernel.proc.SessionRegistry.list(this)
            .firstOrNull { lobos.kernel.proc.SessionRegistry.socketName(it.token) == socketName } ?: return null
        return lobos.kernel.proc.SessionRegistry.verify(this, s.token)
    }

    private class SessionHolder(val socketName: String) {
        @Volatile
        var granted: Set<String> = emptySet()
        @Volatile
        var system: Boolean = false
        @Volatile var session: lobos.kernel.proc.SessionRegistry.Session? = null
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
        val canonical = ApiSpec.canonical(method)
        val short = ApiSpec.shortOf(method)
        val def = METHODS[method] ?: OS_METHODS[method]
            ?: METHODS[short] ?: OS_METHODS[short]
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
            if (def.audit) audit(method, params, true, null, session, result)
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
                "桥协议不兼容：宿主支持 [" + PROTOCOL_MIN + "," + BuildConfig.BRIDGE_PROTOCOL + "]；客户端=" + clientProtocol,
            )
        }
        val negotiated = if (clientProtocol > BuildConfig.BRIDGE_PROTOCOL) BuildConfig.BRIDGE_PROTOCOL else clientProtocol
        val requires = params.optJSONArray("requires")?.toList() ?: emptyList()
        val hint = params.optString("program", "").takeIf { it.isNotBlank() }
        val session = sessionOfSocket(holder.socketName)
        if (session == null) {
            audit("bridge.handshake", params, false, "无有效宿主会话", null)
            return error(id, CODE_SESSION_MISSING, "无有效宿主会话：宿主未签发或会话已失效")
        }
        val provided = params.optString("token", "").trim()
        if (provided.isBlank()) {
            audit("bridge.handshake", params, false, "缺少会话令牌", null)
            return error(id, CODE_SESSION_MISSING, "缺少会话令牌：握手必须携带环境变量 LOBOS_SESSION_TOKEN")
        }
        if (provided != session.token) {
            audit("bridge.handshake", params, false, "会话令牌不符", session)
            return error(id, CODE_SESSION_MISSING, "会话令牌不符：令牌必须来自宿主注入的 LOBOS_SESSION_TOKEN")
        }
        if (holder.session != null) {
            audit("bridge.handshake", params, false, "该连接已激活会话（不允许重复握手）", session)
            return error(id, CODE_SESSION_MISSING, "该连接已完成握手：会话不允许重复激活")
        }
        if (!lobos.kernel.proc.SessionRegistry.claim(this, session.token)) {
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

    private fun serverGranted(session: lobos.kernel.proc.SessionRegistry.Session): Set<String> {
        val id = session.programId.trim()
        if (id == INSTALLER_ID || !isProgramSession(session)) {
            return setOf(BRIDGE_BASE) + ApiSpec.GROUP_SYS
        }
        return setOf(BRIDGE_BASE) + declaredCapabilities(id)
    }

    private fun declaredCapabilities(programId: String): Set<String> {
        val id = programId.trim()
        if (id.isBlank()) return emptySet()
        return runCatching { lobos.services.reg.ProgramIndex.get(this, id)?.capabilities.orEmpty() }
            .getOrDefault(emptySet())
            .toSet()
    }

    private fun isProgramSession(session: lobos.kernel.proc.SessionRegistry.Session): Boolean {
        val id = session.programId.trim()
        if (id.isBlank()) return false
        val declared = lobos.services.reg.ProgramRegistry.listIds(this)
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
                if (ProgramIndex.isPiece(this@CapabilityBroker, name)) {
                    throw BridgeError(
                        CODE_POLICY_DENIED,
                        "系统基础环境不可卸载（可升级、可回退到 APK 基线）：" + name,
                    )
                }
                JSONObject().apply { put("ok", PackageInstaller.uninstall(this@CapabilityBroker, name)) }
            }
            "rollback" -> {
                if (!ProgramIndex.isPiece(this@CapabilityBroker, name)) {
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

    private fun deviceCapabilities(): Set<String> = setOf(BRIDGE_BASE)

    private fun audit(
        method: String,
        params: JSONObject,
        ok: Boolean,
        err: String?,
        session: lobos.kernel.proc.SessionRegistry.Session?,
        result: JSONObject? = null,
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
            val out = result?.let { r ->
                val s = r.toString()
                if (s.length > 400) s.take(400) + "…" else s
            } ?: ""
            val line = ts + " " + who + " method=" + method + " ok=" + ok + " params=" + summary +
                (if (err != null) " err=" + err else "") +
                (if (out.isNotEmpty()) " result=" + out else "") + "\n"
            lobos.kernel.fs.StateFiles.appendBounded(File(filesDir, "bridge-audit.log"), line)
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

    private fun programJson(e: UnitEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("name", e.id)
        put("version", e.version.ifBlank { JSONObject.NULL })
        put("role", e.role)
        put("phase", e.desired.name.lowercase(Locale.US))
    }

    private fun programsJson(): JSONArray = JSONArray().apply {
        ProgramIndex.byLevel(this@CapabilityBroker, Level.PROGRAM).forEach { put(programJson(it)) }
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
        //  缺陷名（C-102）**这一条路逐字用**那 startService（=> 而而那**它**们**逐字是**那**两**件**事**）。
        //    => 而那**成因**：=> 而那 startService 逐字**只**在**那应用**在**前台**的时候**才**能**起**那后台服务**、
        //      => 而而那**它**们**逐字是**那**两**件**事**。
        //    => 而而那**后果**逐字 = 而那宿主服务逐字**起不来**、=> 而而那**落位**那一族**与**那**身份**那一族
        //      逐字**都**只在**那宿主服务起**的那一趟**里**跑**、=> 而而那**它们**逐字**都**没跑**。
        //    => 而那**处置**：=> 而这一条路逐字**走**那 ensureRunning（=> 而而那**它**逐字**是**那**一个**入口**、
        //      => 而而那**它**逐字**跟**那 BootReceiver.startQuietly **那一族**同一**一个**）。
        //    => 而按 C-104：=> 而那宿主那一族逐字**只有**那一个**入口**（=> 而而那**它**们**逐字是**那同一个**触发**的**两种**写法**）。
        lobos.services.host.OsHostService.ensureRunning(this, action)
    }

    private fun instanceJson(e: UnitEntry): JSONObject = JSONObject().apply {
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
            throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：宿主不接受「默认程序」")
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
                if (target.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：宿主不接受「默认程序」")
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
        "lobos.sys.native.status" to MethodDef(listOf("base"), true) { _, _programId ->
            val arr = JSONArray()
            for (s in PieceUpdater.states(this@CapabilityBroker)) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("installedVersion", s.installedVersion ?: JSONObject.NULL)
                    put("updated", s.updated)
                    put("path", s.source?.absolutePath ?: JSONObject.NULL)
                })
            }
            JSONObject().apply {
                put("ok", true)
                put("components", arr)
                put("note", "原件在 APK 里永远保留；updated=true 表示当前用的是 OTA 更新过的那份，回滚只需删软链。")
            }
        },
        "lobos.sys.native.update" to MethodDef(listOf("base"), true) { p, _programId ->
            val dry = p.optBoolean("dryRun", true)
            PieceUpdater.checkAndUpdate(
                this@CapabilityBroker,
                p.optString("manifestUrl", ""),
                dryRun = dry,
            ).let { it.put("dryRun", dry) }
        },
        "lobos.sys.native.rollback" to MethodDef(listOf("base"), true) { p, _programId ->
            val id = p.optString("id", "")
            if (id.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "id 为空")
            val (ok, why) = PieceUpdater.rollback(this@CapabilityBroker, id)
            JSONObject().apply {
                put("ok", ok)
                put("id", id)
                put("detail", why ?: "已回滚到 APK 原件")
            }
        },
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
            ProgramIndex.byLevel(this@CapabilityBroker, Level.PROGRAM).forEach { e ->
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
                throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id（宿主没有「主程序」概念）")
            }
            val e = ProgramIndex.byLevel(this@CapabilityBroker, Level.PROGRAM).firstOrNull { it.id == id }
                ?: throw BridgeError(CODE_METHOD_NOT_FOUND, "无此实例: " + id)
            instanceJson(e)
        },
        "os.instances.action" to MethodDef(listOf("base"), true) { p, _programId ->
            val self = p.optString("id", "")
            val id = p.optString("id", self)
            val spec = lobos.services.reg.ProgramRegistry.spec(this@CapabilityBroker, id)
                ?: throw BridgeError(CODE_METHOD_NOT_FOUND, "无此实例: " + id)
            val action = p.optString("action", "")
            if (action == "stop") {
                Journal.note(
                    this@CapabilityBroker, "instance", false, "实例动作被拒：停止是宿主保留动作",
                    "id=" + id + "（宿主尚未具备按程序独立停止的能力）",
                )
                throw BridgeError(CODE_POLICY_DENIED, "停止是宿主保留动作，应用只能请求启动/重启")
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
                this@CapabilityBroker, "session", false, "会话停止被拒：停用运行时是宿主保留动作",
                "应用无权请求宿主停机；宿主停机只能经宿主内部路径",
            )
            throw BridgeError(CODE_POLICY_DENIED, "停用运行时是宿主保留动作，应用无权调用")
        },
        "os.programs.overview" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply {
                put("installed", ProgramIndex.byLevel(this@CapabilityBroker, Level.PROGRAM).size)
                put("programs", programsJson())
                put("versionInfo", JSONObject().apply {
                    val ids = lobos.services.reg.ProgramRegistry.listIds(this@CapabilityBroker)
                    val single = ids.singleOrNull()?.let { ProgramDir(this@CapabilityBroker, it).currentVersion() }
                    put("current", single ?: JSONObject.NULL)
                    put("programs", JSONObject(ids.associateWith { ProgramDir(this@CapabilityBroker, it).currentVersion() ?: JSONObject.NULL }))
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
                throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id（宿主没有「主程序」概念）")
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
            if (target.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定程序 id：宿主不接受「默认程序」")
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
            RegistryStore.setOrigin(this@CapabilityBroker, origin)
        },
        "os.registry.refresh" to MethodDef(listOf("base"), true) { _, _programId ->
            val cur = RegistryStore.info(this@CapabilityBroker).optString("origin", "")
            if (cur.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "尚未设置镜像源（先 os.registry.set）")
            RegistryStore.setOrigin(this@CapabilityBroker, cur)
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
            val rt = lobos.services.reg.InstalledRuntime.programRuntime(this@CapabilityBroker)
            val res = lobos.services.reg.ResidencyStatus.snapshot()
            JSONObject().apply {
                put("name", rt.id)
                put("version", rt.version)
                put("path", rt.path?.absolutePath ?: JSONObject.NULL)
                put("ok", rt.path != null)
                put("detail", if (rt.path == null) lobos.services.reg.InstalledRuntime.notInstalledHint(this@CapabilityBroker, rt.id) else JSONObject.NULL)
                put("degraded", res.optBoolean("degraded", false))
                put("degradedReasons", res.optJSONArray("degradedReasons") ?: JSONArray())
                put("actions", res.optJSONArray("actions") ?: JSONArray())
                put("accessibilityReady", res.optBoolean("accessibilityReady", false))
                put("programsRunning", res.optInt("programsRunning", 0))
                put("installedPrograms", res.optInt("installedPrograms", 0))
                put("runningIds", res.optJSONArray("runningIds") ?: JSONArray())
                put("tickGapMs", res.optLong("tickGapMs", 0L))
                put("frozen", res.optBoolean("frozen", false))
                put("uptimeMs", res.optLong("uptimeMs", 0L))
                put("residencyAt", res.optLong("updatedAt", 0L))
            }
        },

        "os.runtime.nodeLts" to MethodDef(listOf("base"), false) { _, _programId ->
            val rt = lobos.services.reg.InstalledRuntime.programRuntime(this@CapabilityBroker)
              val cur = lobos.services.reg.InstalledRuntime.versionOf(this@CapabilityBroker, rt.id)
            val latest = runCatching {
                val arr = CatalogClient.entries(this@CapabilityBroker)
                var v = ""
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    if (t.optString("name", "") == rt.id) {
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
            lobos.services.app.DriverRegistry.report(this@CapabilityBroker)
        },

        "os.env.status" to MethodDef(listOf("base"), false) { _, _programId ->
            val owner = runCatching {
                lobos.device.DeviceOwnerState.measure(this@CapabilityBroker)
            }.getOrNull()
            JSONObject().apply {
                put("platform", "android")
                put("apiLevel", Build.VERSION.SDK_INT)
                put("deviceOwner", owner?.isDeviceOwner == true)
            }
        },
        "os.env.programs" to MethodDef(listOf("base"), false) { _, _programId ->
            JSONObject().apply { put("programs", programsJson()) }
        },
        "os.manifest.spec" to MethodDef(listOf("base"), false) { _, _programId ->
            lobos.services.reg.ManifestSchema.spec().apply {
                put("restarts", JSONArray(lobos.services.reg.ManifestSchema.RESTARTS.toList()))
                put("uiTypes", JSONArray(lobos.services.reg.ManifestSchema.UI_TYPES.toList()))
                put("onUiClosed", JSONArray(lobos.services.reg.ManifestSchema.ON_CLOSED.toList()))
                put("restartAliases", JSONObject().apply {
                    for ((k, v) in lobos.services.reg.ManifestSchema.RESTART_ALIASES) put(k, v)
                })
            }
        },
        "os.manifest.validate" to MethodDef(listOf("base"), false) { p, _programId ->
            val id = p.optString("id", "")
            if (id.isBlank()) throw BridgeError(CODE_INVALID_PARAM, "必须显式指定 id")
            val spec = lobos.services.reg.ProgramRegistry.spec(this@CapabilityBroker, id)
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
                lobos.services.reg.ManifestSchema.toJson(json).apply {
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
            val report = runCatching { PieceProvisioner.prepare(this@CapabilityBroker) }.getOrNull()
            JSONObject().apply {
                put("present", report != null)
                put("snapshot", report?.toJson() ?: JSONObject.NULL)
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
        "sys.tty.list" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { _, _programId ->
        val arr = JSONArray()
        for (one in lobos.kernel.ipc.PtySession.activeSessions(this@CapabilityBroker)) {
            arr.put(JSONObject().put("sid", one.sid).put("slave", one.slavePath))
        }
        JSONObject().put("sessions", arr).put("count", arr.length())
    },
    // preferPty=false 走无 PTY 通路（LocalExec.runPlain）。今天用它做判别：
    // 同一条命令在 PTY 与非 PTY 下各跑一次，退出码与 stderr 一起回 ——
    // 只在 PTY 下崩（exit 139）就把范围收在 PTY 那侧，不在 argv 替换那一侧。
    "sys.tty.exec" to MethodDef(listOf(ApiSpec.GROUP_SYS), true) { p, programId ->
        val cmd = p.optString("cmd", "")
        if (cmd.isBlank()) {
            throw BridgeError(CODE_INVALID_PARAM, "cmd 不能为空")
        }
        val sh = lobos.services.supply.ShellEntry.require(this@CapabilityBroker)
        val r = lobos.kernel.ipc.LocalExec.run(
            this@CapabilityBroker,
            listOf(sh.absolutePath, "-c", cmd),
            timeoutMs = p.optLong("timeoutMs", 10_000L),
            preferPty = !p.optBoolean("noPty", false),
        )
        execAsJson(r)
    },
    "os.permissions.ledger" to MethodDef(listOf(ApiSpec.GROUP_SYS), false) { p, _programId ->
