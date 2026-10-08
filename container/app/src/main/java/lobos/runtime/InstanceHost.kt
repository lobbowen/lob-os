package lobos.runtime

import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import lobos.RuntimeDiagnostics
import lobos.lifecycle.OsHostService
import lobos.pieces.AssetStatus
import lobos.pieces.PieceProvisioner
import lobos.os.ProgramDir
import lobos.ota.ProgramOtaResolution
import lobos.ota.ProgramOtaUpdater
import org.json.JSONObject

class InstanceHost(private val host: Service, val programId: String) : ContextWrapper(host) {

    private var nodeProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val bootExec = Executors.newSingleThreadExecutor()
    @Volatile private var bootLoopActive = false
    @Volatile private var keepRunning = true

    @Volatile private var stagingSwept = false
    @Volatile private var quarantineReset = false
    @Volatile private var currentSpec: lobos.os.ProgramRegistry.Spec? = null
    private var healthUp = false
    private var healthPort = 0
    private var healthPath = "/status"
    private var currentGeneration = 0L


    private val libSearchPath: String get() = PieceProvisioner.libSearchPath(this)

    fun start() {
        scheduleBootLoop()
    }

    fun onHostStart(intent: Intent?) {
        when (intent?.action) {
            ACTION_RESTART -> requestRestart()
            ACTION_STOP_RUNTIME -> requestStop()
            ACTION_START_RUNTIME -> requestStart()
        }
        scheduleBootLoop()
    }

    fun requestStop() {
        keepRunning = false
        try { reapProgramTree("外部请求停止") } catch (_: Throwable) { }
    }

    fun requestStart() {
        keepRunning = true
        quarantineReset = true
        scheduleBootLoop()
    }

    fun requestRestart() {
        keepRunning = true
        quarantineReset = true
        try { reapProgramTree("外部请求重启") } catch (_: Throwable) { }
        scheduleBootLoop()
    }

    private fun acquireBriefWakeLock(timeoutMs: Long) {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lobos:runtime").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) { }
        wakeLock = null
    }

    @Synchronized
    private fun scheduleBootLoop() {
        if (bootLoopActive) return
        bootLoopActive = true
        bootExec.execute {
            try {
                bootLoop()
            } finally {
                bootLoopActive = false
            }
        }
    }

        // 重启计数在注册表里（NRestarts 是持久属性，不是内存计数）——
        // systemctl(1)：「expose RUNTIME STATE IN ADDITION TO CONFIGURATION」
        val restartCount: Int get() =
            lobos.os.ProgramIndex.get(this, programId)?.restarts ?: 0
    private fun bootLoop() {
        while (keepRunning) {
            acquireBriefWakeLock(60_000L)
            val outcome = try { bootProgramOnce() } finally { releaseWakeLock() }
            val bootOk = SupervisorPolicy.bootSucceeded(outcome)
            var bornAt = 0L
            var failStreak = 0
            if (bootOk) {
                bornAt = SystemClock.elapsedRealtime()
                var lastCheck = 0L
                while (keepRunning && nodeProcess?.isAlive == true && healthUp) {
                    sleepQuiet(1000L)
                    if (!keepRunning) break
                    if (healthPort <= 0) continue
                    val now = SystemClock.elapsedRealtime()
                    if (lastCheck != 0L && now - lastCheck < SupervisorPolicy.CHECK_INTERVAL_MS) continue
                    lastCheck = now
                    if (isStatusUp()) {
                        if (failStreak > 0) {
                            RuntimeDiagnostics.append(this, "health", true, "稳态探活恢复", "此前连续失败=" + failStreak)
                        }
                        failStreak = 0
                        lobos.os.ProgramStatusHub.publishHealth(programId, true, "控制面就绪")
                    } else {
                        failStreak += 1
                        RuntimeDiagnostics.append(
                            this, "health", false, "稳态探活失败",
                            "连续=" + failStreak + "/" + SupervisorPolicy.HEALTH_FAIL_THRESHOLD,
                        )
                        lobos.os.ProgramStatusHub.publishHealth(programId, false, "探活失败×" + failStreak)
                        if (!SupervisorPolicy.healthyByStreak(failStreak)) {
                            healthUp = false
                            break
                        }
                    }
                }
            }
            if (!keepRunning) break
            if (outcome == SupervisorPolicy.BootOutcome.NO_PROGRAM) {
                RuntimeDiagnostics.append(
                    this, "supervisor", false, "无可用程序：长退避后重试（不再永久停手）",
                    "退避=" + SupervisorPolicy.NO_PROGRAM_BACKOFF_MS + "ms",
                )
                sleepQuiet(SupervisorPolicy.NO_PROGRAM_BACKOFF_MS)
                continue
            }
            // 重启计数落注册表（NRestarts 是持久属性）—— systemctl(1)：
            // 「expose RUNTIME STATE IN ADDITION TO CONFIGURATION」
            val aliveMs = SystemClock.elapsedRealtime() - bornAt
            if (bootOk && aliveMs >= SupervisorPolicy.STABLE_MS) {
                runCatching {
                    lobos.os.ProgramIndex.mutate(this@InstanceHost, programId) {
                        it.edited(restarts = 0)
                    }
                }
            } else {
                SupervisorPolicy.noteRestart(this@InstanceHost, programId, SystemClock.elapsedRealtime())
            }
                restartCount, bootOk, SystemClock.elapsedRealtime() - bornAt,
            )
            val now = SystemClock.elapsedRealtime()
            val windowNow: Int =
                lobos.os.ProgramIndex.get(this@InstanceHost, programId)?.restarts ?: 0
            val maxNow: Int =
                lobos.os.ProgramIndex.get(this@InstanceHost, programId)?.maxRestarts
                    ?.takeIf { it > 0 } ?: Int.MAX_VALUE
            if (windowNow >= maxNow) {
                lobos.os.ProgramStatusHub.publishQuarantined(programId, true)
                RuntimeDiagnostics.append(
                    this, "supervisor", false, "进入隔离（QUARANTINED）：重启过密",
                    "窗口内重启 " + windowNow + " 次（上限 " + maxNow + "）· " + SupervisorPolicy.RESTART_WINDOW_MS + "ms 窗口" +
                )
                quarantineReset = false
                var quarantinePolls = 0
                while (keepRunning && !quarantineReset) {
                    sleepQuiet(SupervisorPolicy.QUARANTINE_POLL_MS)
                    quarantinePolls += 1
                    if (quarantinePolls >= SupervisorPolicy.QUARANTINE_HALF_OPEN_POLLS) {
                        RuntimeDiagnostics.append(
                            this, "supervisor", null, "隔离半开：允许一次试探性重启",
                            "已等待 " + quarantinePolls + " 个轮询周期",
                        )
                        break
                    }
                }
                if (!keepRunning) break
                runCatching {
                    lobos.os.ProgramIndex.mutate(this@InstanceHost, programId) {
                        it.edited(restarts = 0)
                    }
                }
                RuntimeDiagnostics.append(this, "supervisor", true, "隔离已解除（宿主重置）", "重新开始监督")
                continue
            }
            val spec = currentSpec
            if (spec != null && spec.restart == lobos.os.Restart.NEVER) {
                RuntimeDiagnostics.append(
                    this, "supervisor", false, "清单声明 restart=never：不再重启该程序",
                    "id=" + spec.id + " programId=" + programId,
                )
                keepRunning = false
                break
            }
            if (spec != null && spec.maxRestarts in 1..restartCount) {
                RuntimeDiagnostics.append(
                    this, "supervisor", false, "清单 maxRestarts 已达上限：转入隔离",
                    "id=" + spec.id + " maxRestarts=" + spec.maxRestarts + " attempt=" + restartCount,
                )
                runCatching {
                    lobos.os.ProgramIndex.mutate(this@InstanceHost, programId) {
                        it.edited(restarts = it.maxRestarts)
                    }
                }
            }
            val declaredBackoff = spec?.backoffMs?.takeIf { it.isNotEmpty() }
                ?.let { list -> list[minOf(restartCount, list.size - 1)] }
            val backoff = declaredBackoff ?: SupervisorPolicy.backoffFor(outcome, restartCount)
            lobos.os.ProgramStatusHub.publishRestarts(programId, restartCount)
            RuntimeDiagnostics.append(
                this, "supervisor", null, "退避 ${backoff}ms 后重启",
                "attempt=$restartCount 来源=" + (if (declaredBackoff != null) "清单 backoff" else "策略默认"),
            )
            sleepQuiet(backoff)
        }
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun bootProgramOnce(): SupervisorPolicy.BootOutcome {
        try {
            RuntimeDiagnostics.append(
                this, "init", null, "InstanceHost 启动程序进程",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), filesDir=${filesDir.absolutePath}"
            )

            OsHostService.ensureRunning(this)

            val km = ProgramDir(this, programId)

            if (!stagingSwept) {
                stagingSwept = true
                val rep = try {
                    lobos.os.BootReconciler.run(this)
                } catch (e: Throwable) {
                    RuntimeDiagnostics.append(this, "boot", false, "启动对账异常", err(e))
                    null
                }
                if (rep != null) {
                    RuntimeDiagnostics.append(
                        this, "boot", !rep.changed, "启动对账完成", rep.summary()
                    )
                }
            }

            val otaCfg = ProgramOtaUpdater.loadConfig(this)
            if (otaCfg != null && otaCfg.autoCheck) {
                try {
                    val ota = ProgramOtaUpdater.checkAndUpdate(this, km, budgetMs = otaCfg.startupBudgetMs)
                    if (ota.checked) {
                        RuntimeDiagnostics.append(
                            this, "program-ota", ota.updated || ota.upToDate,
                            if (ota.updated) "启动自动升级控制面板到 ${ota.remote}" else "启动控制面板检查完成（无更新）",
                            ota.detail
                        )
                    }
                } catch (e: Throwable) {
                    RuntimeDiagnostics.append(
                        this, "program-ota", false, "启动控制面板检查异常",
                        "${e::class.java.simpleName}: ${e.message}"
                    )
                }
            }

            val spec = lobos.os.ProgramRegistry.spec(this, programId)
            currentSpec = spec
            val resolvedPort = lobos.os.ProgramManager.resolveHttpPort(this, programId, spec?.http?.port ?: 0)
            val settings = lobos.os.ProgramSettings.read(this).optJSONObject(programId)
            val argsOverride = settings?.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            val envOverride = settings?.optJSONObject("env")?.let { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } } ?: emptyMap()
            val declaredSafe = lobos.os.RuntimeEnvironment.withoutReserved(spec?.env ?: emptyMap())
            val overrideSafe = lobos.os.RuntimeEnvironment.withoutReserved(envOverride)
            if (declaredSafe.second.isNotEmpty() || overrideSafe.second.isNotEmpty()) {
                lobos.log.Journal.note(
                    this, "settings", false, "保留环境变量被拒（程序不得改写宿主注入面）",
                    "id=" + programId + " 清单丢弃=" + declaredSafe.second.joinToString(",") +
                        " 设置丢弃=" + overrideSafe.second.joinToString(","),
                )
            }
            if (argsOverride != null || envOverride.isNotEmpty()) {
                lobos.log.Journal.note(
                    this, "settings", null, "程序设置生效（宿主在 spawn 时叠加）",
                    "id=" + programId + " args=" + (argsOverride?.joinToString(" ") ?: "(按清单)") +
                        " env键=" + envOverride.keys.joinToString(","),
                )
            }
            val kVersion = spec?.version ?: km.currentVersion()
            val programDir = spec?.dir ?: if (!kVersion.isNullOrBlank()) km.programDir(kVersion) else null
            val entry = spec?.entryFile ?: if (!kVersion.isNullOrBlank()) km.entryPath(kVersion) else null
            if (spec != null && (spec.invalid != null || spec.note != null)) {
                RuntimeDiagnostics.append(
                    this, "program-manifest", spec.invalid == null,
                    "程序清单问题：" + spec.id + "（" + spec.version + "）",
                    (spec.invalid?.let { "阻断：" + it } ?: "") +
                        (if (spec.invalid != null && spec.note != null) "；" else "") +
                        (spec.note?.let { "提示：" + it } ?: "")
                )
            }
            val res = ProgramOtaResolution.resolve(kVersion, entry?.absolutePath, entry != null && entry.exists())
            if (res.ok && kVersion != null) {
                try {
                    km.assertNotDirectlyExecutable(kVersion)
                } catch (e: IllegalStateException) {
                    RuntimeDiagnostics.append(this, "program", false, "程序入口布局异常", err(e))
                    return SupervisorPolicy.BootOutcome.FAILED
                }
            }
            val integrity = km.integrityChecks()
            if (integrity.isNotEmpty()) {
                RuntimeDiagnostics.append(
                    this, "program-integrity", false, "程序布局不自洽", integrity.joinToString("; ")
                )
            }
            RuntimeDiagnostics.append(this, "program", res.ok, res.title, res.detail)

            val nodeBinForVersion = lobos.runtime.InstalledRuntime.binOf(this, InstalledRuntime.programRuntime(this).id)
            RuntimeDiagnostics.append(
                this, "version", nodeBinForVersion != null,
                if (nodeBinForVersion != null) "Node 运行时版本=" + lobos.runtime.InstalledRuntime.versionOf(this, InstalledRuntime.programRuntime(this).id)
                else "Node 运行时缺失",
                "路径=" + (nodeBinForVersion?.absolutePath ?: lobos.runtime.InstalledRuntime.notInstalledHint(this, InstalledRuntime.programRuntime(this).id)),
            )

            val assets = PieceProvisioner.prepare(this)
            if (!assets.allRequiredReady) {
                val what = assets.failedRequired.joinToString("; ") { (e, st) ->
                    "${e.libName}（${describeStatus(st)}）"
                }
                RuntimeDiagnostics.append(
                    this, "provision", false, "系统件校验未通过，中止启动", what
                )
                return SupervisorPolicy.BootOutcome.FAILED
            }
            val nodeBin = lobos.runtime.InstalledRuntime.binOf(this, InstalledRuntime.programRuntime(this).id) ?: run {
                RuntimeDiagnostics.append(
                    this, "runtime", false,
                    "node 运行时未就位，本次不启动程序（会按退避重试）",
                    lobos.runtime.InstalledRuntime.notInstalledHint(this, InstalledRuntime.programRuntime(this).id) +
                        "；件由「装程序时按该程序 requires 决定」安装（走 os/PackageInstaller），" +
                        "装好后下一次重试即自动起来 —— 首次开机可能需要等一个退避周期",
                )
                return SupervisorPolicy.BootOutcome.FAILED
            }

            if (!res.ok) return SupervisorPolicy.BootOutcome.NO_PROGRAM

            val env = lobos.os.RuntimeEnvironment.ensure(this)
            val tree = lobos.os.RuntimeEnvironment.treeRootFor(this, env)
            val treeEnv = lobos.os.RuntimeEnvironment.treeRootEnv(tree, getenv("PATH"))

            writeRuntimeJson(
                nodePath = nodeBin.absolutePath,
                nodeBinDir = nodeBin.parentFile!!.absolutePath,
                prefix = PrefixProvisioner.root(this).absolutePath,
                minNode = lobos.runtime.InstalledRuntime.versionOf(this, InstalledRuntime.programRuntime(this).id),
                envSnapshot = treeEnv,
            )
            RuntimeDiagnostics.append(this, "runtime", true, "runtime.json 已写入（schema 3，含实际 env 快照）", "home=${filesDir.absolutePath}")

            reapOrphanKernel()

            val kernelDir = programDir!!
            val kernelEntry = entry!!
            val nativeDir = nodeBin.parentFile!!
            currentGeneration = lobos.os.ProcessLedger.nextGeneration(this, spec?.id ?: "")
            val sessionToken = lobos.bridge.CapabilityBroker.prepareSession(this, spec?.id ?: "", currentGeneration)
            val plan = GuestAdapter.programPlan(
                GuestAdapter.ProgramInputs(
                    root = lobos.os.RuntimeEnvironment.treeRootFor(this, env),
                    nodeBin = requireNotNull(lobos.runtime.InstalledRuntime.binOf(this, InstalledRuntime.programRuntime(this).id)) {
                        "node 运行时未安装 —— 程序要用它起（" +
                            lobos.runtime.InstalledRuntime.notInstalledHint(this, InstalledRuntime.programRuntime(this).id) + "）"
                    },
                    programDir = kernelDir,
                    programEntry = kernelEntry,
                    uiDir = File(kernelDir, "ui/dist"),
                    flockSo = lobos.os.PieceScan.pieceFile(this, FLOCK_ID),
                    programId = spec?.id ?: "",
                    args = argsOverride ?: (spec?.args ?: emptyList()),
                    httpPort = resolvedPort,
                    httpEnv = spec?.http?.env,
                    declaredEnv = declaredSafe.first + overrideSafe.first,
                    sessionToken = sessionToken,
                ),
                getenv("PATH"),
            )
            val spawned = ProcessSupervisor.spawn(
                command = plan.command,
                cwd = plan.cwd,
                env = plan.env,
                envMode = ProcessSupervisor.ENV_CLEAR,
                owner = ProcessSupervisor.OWNER_PROGRAM,
                programId = programId,
            )
            nodeProcess = spawned.process
            healthUp = false
            var launchedPid = spawned.pid
            if (launchedPid <= 0) {
                val pidDeadline = SystemClock.elapsedRealtime() + 3_000L
                while (launchedPid <= 0 && SystemClock.elapsedRealtime() < pidDeadline) {
                    launchedPid = lobos.os.ProcessLedger.scanChildPid(entry.absolutePath)
                    if (launchedPid <= 0) sleepQuiet(100L)
                }
            }
            if (launchedPid <= 0) {
                RuntimeDiagnostics.append(
                    this, "process", false, "无法从 /proc 确认自建子进程（身份不可证）",
                    "按 spawn 失败处理：杀掉进程、不写账本、交监督环退避重试",
                )
                reapProgramTree("宿主回收进程树")
                return SupervisorPolicy.BootOutcome.FAILED
            }
            val recorded = lobos.os.ProcessLedger.begin(this, programId, currentGeneration, launchedPid)
            if (launchedPid > 0 && !sessionToken.isNullOrBlank()) {
                lobos.os.SessionRegistry.bindPid(this, sessionToken, launchedPid)
            }
            RuntimeDiagnostics.append(
                this, "ledger", recorded != null, "进程归属已记账",
                if (recorded != null) {
                    "program=" + recorded.programId + " gen=" + recorded.generation + " pid=" + recorded.pid +
                        " starttime=" + recorded.starttime + " pgid=" + recorded.pgid
                } else "pid 无法取得，账本未记录"
            )
            healthPort = resolvedPort
            healthPath = spec?.http?.health ?: "/status"
            val healthDesc = if (healthPort > 0) healthPort.toString() + healthPath else "(清单未声明健康端点)"
            RuntimeDiagnostics.append(
                this, "exec", true, "程序进程已启动",
                "pid=${currentPid(nodeProcess)}, program=${spec?.id ?: "?"}, 健康判据 127.0.0.1:${healthDesc}"
            )

            forward(nodeProcess!!.inputStream, "stdout")
            forward(nodeProcess!!.errorStream, "stderr")
            watchExit()
            if (healthPort > 0) {
                pollControlPlane()
            } else {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                healthUp = nodeProcess?.isAlive == true
                RuntimeDiagnostics.append(
                    this, "health", healthUp, "清单未声明健康端点，按进程存活判定",
                    "program=${spec?.id ?: "?"}"
                )
            }
            return if (healthUp) SupervisorPolicy.BootOutcome.RUNNING else SupervisorPolicy.BootOutcome.FAILED
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "fatal", false, "启动流程异常", err(e))
            Log.e(TAG, "启动程序进程失败", e)
            return SupervisorPolicy.BootOutcome.FAILED
        }
    }

    private fun describeStatus(st: AssetStatus): String = when (st) {
        is AssetStatus.Ready -> "就位"
        is AssetStatus.MissingFromLib ->
            if (st.inApk)
                "APK 里有但没解压出来（查 jniLibs 与 extractNativeLibs）"
            else
                "APK 里就没有（打包期丢失：查构建脚本产物）"
        is AssetStatus.MissingDependency ->
            "缺少依赖 ${st.dep} —— 依赖要随本体同目录，或本体自带含 \\$ORIGIN 的 DT_RUNPATH"
        is AssetStatus.Mismatched ->
            "与登记不符：${st.mismatched.joinToString(", ")} —— 跑 verify 看差在哪，重新铺一次"
        is AssetStatus.NotExecutable ->
            "无法 exec（依赖已确认完好 → SELinux 拒 exec，查那个文件是否真在可执行位置）"
        is AssetStatus.Unusable ->
            "起不来 exit=${st.exit}，输出=${st.output.ifBlank { "(空)" }}"
    }

    private fun currentPid(p: Process?): String {
        if (p == null) return "n/a"
        return runCatching {
            Regex("""pid=(\d+)""").find(p.toString())?.groupValues?.get(1)
        }.getOrNull() ?: "n/a"
    }

    private fun reapProgramTree(reason: String) {
        val e = runCatching {
            lobos.os.ProcessLedger.list(this)
                .firstOrNull { it.programId == programId && it.generation == currentGeneration }
        }.getOrNull()
        val pid = e?.pid ?: -1
        val descendants = if (e != null) lobos.os.ProcessLedger.descendantsOf(pid) else emptyList()
        if (e != null) {
            val n = lobos.os.ProcessLedger.killTree(e)
            RuntimeDiagnostics.append(
                this, "supervisor", n > 0,
                "按账本回收进程树（pid + 后代）",
                reason + " pid=" + pid + " 后代=" + descendants.size + " 已发信号=" + n +
                    "（通用 APK 无法建进程组：子进程继承宿主组，故按 ppid 链扫后代）",
            )
        } else if (pid > 0) {
            RuntimeDiagnostics.append(
                this, "supervisor", false, "账本无本世代条目，仅销毁 Java 侧句柄",
                reason + " pid=" + pid,
            )
        }
        try { nodeProcess?.destroy() } catch (_: Throwable) {}
        if (pid > 0) runCatching { lobos.os.ProcessLedger.end(this, pid) }
    }

    private fun reapOrphanKernel() {
        reapProgramTree("宿主回收进程树")
        RuntimeDiagnostics.clearNodeStderr(this)
        val owned = lobos.os.ProcessLedger.liveOwned(this)
        val reused = lobos.os.ProcessLedger.pidReused(this)
        val gone = lobos.os.ProcessLedger.gone(this)
        for (e in owned) {
            RuntimeDiagnostics.append(
                this, "reap", null, "回收上世遗留进程",
                "pid=" + e.pid + " starttime=" + e.starttime + " program=" + e.programId + " gen=" + e.generation
            )
            stopProcessTree(e.pid)
            lobos.os.ProcessLedger.end(this, e.pid)
        }
        for (e in reused) {
            RuntimeDiagnostics.append(
                this, "reap", null, "账本 pid 已被复用：不杀",
                "pid=" + e.pid + " ledgerStart=" + e.starttime + " nowStart=" + lobos.os.ProcessLedger.starttimeOf(e.pid)
            )
            lobos.os.ProcessLedger.end(this, e.pid)
        }
        if (gone.isNotEmpty()) {
            gone.forEach { lobos.os.ProcessLedger.end(this, it.pid) }
            RuntimeDiagnostics.append(this, "reap", null, "账本清理已消失进程", gone.joinToString(",") { it.pid.toString() })
        }
    }

    private fun stopProcessTree(pid: Int) {
        if (pid <= 0) return
        val me = android.os.Process.myPid()
        if (pid == me) {
            RuntimeDiagnostics.append(this, "supervisor", false, "拒绝自杀：账本 pid 是宿主自己", "pid=" + pid)
            return
        }
        try { android.os.Process.sendSignal(pid, 15) } catch (_: Throwable) {}
        var waited = 0
        while (waited < 3000 && lobos.os.ProcessLedger.starttimeOf(pid) > 0) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
            waited += 200
        }
        if (lobos.os.ProcessLedger.starttimeOf(pid) > 0) {
            try { android.os.Process.killProcess(pid) } catch (_: Throwable) {}
            try { Thread.sleep(200) } catch (_: InterruptedException) { }
        }
    }

    private fun pidOf(p: Process?): Int {
        if (p == null) return -1
        return runCatching {
            Regex("""pid=(\d+)""").find(p.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }.getOrDefault(-1)
    }



    private fun forward(stream: InputStream, tag: String) {
        val t = Thread {
            try {
                var shown = 0
                var childShown = 0
                stream.bufferedReader().use { r ->
                    r.forEachLine { line ->
                        Log.i("Program:$tag", line)
                        if (tag == "stderr") {
                            RuntimeDiagnostics.recordNodeStderr(this, line + "\n")
                            val isChildLine = line.startsWith("[stderr] ")
                            if (isChildLine) {
                                if (childShown < CHILD_STDERR_SCREEN_LINES) {
                                    RuntimeDiagnostics.append(this, "program-stderr", null, line)
                                    childShown++
                                    if (childShown == CHILD_STDERR_SCREEN_LINES) {
                                        RuntimeDiagnostics.append(this, "program-stderr", null, "……(子进程 stderr 上屏截断，完整见 node-stderr.log)")
                                    }
                                }
                            } else if (shown < STDERR_SCREEN_LINES) {
                                RuntimeDiagnostics.append(this, "program-stderr", null, line)
                                shown++
                                if (shown == STDERR_SCREEN_LINES) {
                                    RuntimeDiagnostics.append(this, "program-stderr", null, "……(stderr 上屏截断，完整见 node-stderr.log)")
                                }
                            }
                        } else {
                            RuntimeDiagnostics.append(this, "program-$tag", null, line)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w("Program:$tag", "转发线程结束（不影响运行时存活）", e)
                RuntimeDiagnostics.append(
                    this, "program-$tag", null,
                    "转发线程结束：${e::class.java.simpleName}: ${e.message}",
                    "tag=$tag —— 这条线程死掉只该丢掉日志转发，绝不许拖垮 runtime（真机 D10）"
                )
            }
        }
        t.name = "program-$tag-forward"
        t.start()
    }

    private fun watchExit() {
        val p = nodeProcess ?: return
        val watchedPid = pidOf(p)
        Thread {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            lobos.os.ProcessLedger.end(this, watchedPid)
            if (!keepRunning) return@Thread
            val readyNote = SupervisorPolicy.exitNote(healthUp)
            RuntimeDiagnostics.append(this, "process", false, "程序进程已退出", "exitCode=$code$readyNote")

            var err = RuntimeDiagnostics.readNodeStderr(this)
            var waited = 0
            while (err.isBlank() && waited < 1500) {
                Thread.sleep(100)
                waited += 100
                err = RuntimeDiagnostics.readNodeStderr(this)
            }

            RuntimeDiagnostics.append(
                this, "node-stderr", err.isNotBlank(), "node 标准错误(完整)",
                if (err.isNotBlank()) err
                else "(node 确实没有 stderr 输出；已等待 ${waited}ms 让转发线程收敛。\n" +
                    " stdout 已逐行写入 logcat，可用 adb logcat -s InstanceHost:*)"
            )
        }.start()
    }

    private fun pollControlPlane() {
        var waitedMs = 0
        var procDiedEarly = false
        while (waitedMs < HEALTH_POLL_BUDGET_MS) {
            if (nodeProcess?.isAlive != true) { procDiedEarly = true; break }
            if (isStatusUp()) {
                healthUp = true
                commitPendingKernel()
                RuntimeDiagnostics.append(
                    this, "health", true,
                    "程序健康就绪 (127.0.0.1:" + healthPort + healthPath + ")",
                    "程序运行成功 ✓"
                )
                return
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
            waitedMs += 300
        }
        rollbackIfPendingFailed()
        lobos.os.ProgramStatusHub.publishHealth(programId, false, "控制面未在预算内就绪")
        RuntimeDiagnostics.append(
            this, "health", false,
            if (procDiedEarly) "程序进程已退出，控制面不会就绪（等待 ${waitedMs}ms 提前收轮）"
            else "控制面在 ${HEALTH_POLL_BUDGET_MS}ms 内未就绪",
            "可能原因：程序进程崩溃 / 端口被占用 / 二进制不兼容当前 ROM（如非 16KB 页对齐）。\n" +
                "查看上方 [FAIL] process 与 node-stderr。"
        )
    }

    private fun commitPendingKernel() {
        runCatching {
            val cfg = ProgramOtaUpdater.loadConfig(this)
            if (cfg != null) ProgramOtaUpdater.promotePendingSequence(this, cfg, programId)
        }
        try {
            val km = ProgramDir(this, programId)
            val pend = km.pending() ?: return
            if (pend.version != km.currentVersion()) return
            km.setFloor(pend.version)
            km.clearPending()
            RuntimeDiagnostics.append(
                this, "program-commit", true,
                "程序 " + pend.version + " 已提交（版本下限提升）",
                "from=" + (pend.from ?: "(无)") + "；floor=" + (km.floorVersion() ?: "(未设)")
            )
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "program-commit", false, "Program 提交失败", err(e))
        }
    }

    private fun rollbackIfPendingFailed() {
        runCatching {
            val cfg = ProgramOtaUpdater.loadConfig(this)
            if (cfg != null) ProgramOtaUpdater.dropPendingSequence(this, cfg, programId)
        }
        try {
            val km = ProgramDir(this, programId)
            val pend = km.pending() ?: return
            val from = pend.from ?: return
            if (km.rollbackTo(from)) {
                RuntimeDiagnostics.append(
                    this, "program-rollback", false,
                    "程序 " + pend.version + " 未通过健康检查，已回滚到 " + from,
                    "版本下限保持 " + (km.floorVersion() ?: "(未设)") + " 不变（防止回退后再被更旧的包覆盖）"
                )
            }
            km.clearPending()
        } catch (_: Throwable) { }
    }

    private fun isStatusUp(): Boolean {
        if (healthPort <= 0) return false
        return try {
            val c = URL("http://127.0.0.1:" + healthPort + healthPath).openConnection() as HttpURLConnection
            c.connectTimeout = 300
            c.readTimeout = 1500
            c.requestMethod = "GET"
            try { c.responseCode == 200 } finally { c.disconnect() }
        } catch (_: Throwable) {
            false
        }
    }




    private fun getenv(k: String): String? = System.getenv(k)

    private fun writeRuntimeJson(
        nodePath: String,
        nodeBinDir: String,
        prefix: String,
        minNode: String,
        envSnapshot: Map<String, String> = emptyMap(),
    ) {
        val dir = SystemDirs.run(ctx).let { File(it, "proc") }
        dir.mkdirs()
        val obj = JSONObject().apply {
            put("schema", 3)
            put("nodePath", nodePath)
            put("nodeBinDir", nodeBinDir)
            put("prefix", prefix)
            put("minNode", minNode)
            put("writtenBy", "lobos-os")
            if (envSnapshot.isNotEmpty()) {
                put("env", JSONObject(envSnapshot.toMap()).toString())
            }
        }
        lobos.os.StateFiles.writeAtomic(File(dir, "runtime.json"), obj.toString(2))
    }

    private fun err(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message}\n" +
            e.stackTraceToString().lines().take(10).joinToString("\n")

    fun shutdown() {
        keepRunning = false
        reapProgramTree("宿主关停")
        nodeProcess = null
        bootExec.shutdownNow()
        releaseWakeLock()
    }

    companion object {
        const val TAG = "InstanceHost"
        const val ACTION_RESTART = "lobos.action.RESTART_RUNTIME"

        const val ACTION_STOP_RUNTIME = "lobos.action.STOP_RUNTIME"
        const val ACTION_START_RUNTIME = "lobos.action.START_RUNTIME"

        const val STDERR_SCREEN_LINES = 60
        const val CHILD_STDERR_SCREEN_LINES = 400
    }
}
