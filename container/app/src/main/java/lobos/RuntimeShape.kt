package lobos

object RuntimeShape {

    const val COMPONENTS = 11
    const val METHODS = 72
    const val KT_FILES = 101
    const val KT_LINES = 13801

    const val ROOT_SYS = "filesDir/sys"
    const val ROOT_PROGRAMS = "filesDir/programs"
    const val ROOT_USR = "filesDir/usr"
    const val ROOT_OS = "filesDir/os"
    const val ROOT_SUPERVISOR = "filesDir/supervisor"
    const val ROOT_ADB = "filesDir/adb"

    const val KEEP_ALIVE = "keep-alive"
    const val OPTIONAL_COMPONENT = "optional-component"
    const val DEV_API = "lobos.dev"
    const val SYS_API = "lobos.sys"

    fun layers(): String = buildString {
        append("运行层（无条件自愈，不受任何开关影响）\n")
        append("  ├─ AccessibilityAnchor：uid 被冻结时系统仍放行无障碍服务执行\n")
        append("  │    ensureBound 每拍检查，掉了先摘再挂；本地写 secure 设置，无 WRITE_SECURE_SETTINGS 时借 ADB\n")
        append("  └─ 通知监听：第二个常驻锚\n")
        append("\n功能组件层（受开关控制，不自愈）\n")
        append("  └─ ADB 组件（OX 段，可选）\n")
        append("       ├─ 开发者选项 → 无线调试 → 配对凭据 → ADB 通道\n")
        append("       └─ UI 自动化（子开关，全开全关）→ 授予 accessibility 能力组\n")
        append("            开启即经 ADB 静默打开无障碍；关闭则 ui.* 一律 CODE_CAPABILITY_MISSING\n")
        append("\n保活与自动化拆开的理由\n")
        append("  ensureBound 不看任何开关，只看锚掉没掉；若两者共用一条自愈路径，\n")
        append("  锚自愈会连带把自动化授权也重开，两套关注点绑死，出故障会互相放大。\n")
    }

    fun components(): String = buildString {
        append("Android 组件（" + COMPONENTS + "）\n")
        append("  service   OsHostService        前台常驻：主节拍、锚自愈、Doze 兜底、权限登记\n")
        append("  service   OsAccessibilityService  无障碍服务实例（保活锚 + 自动化执行体）\n")
        append("  service   OsNotificationListenerService  通知监听（第二锚）\n")
        append("  service   ScreenCaptureService  截屏会话承载\n")
        append("  service   StatusTileService    通知栏磁贴\n")
        append("  service   PairingProbeService  配对现场探针（ADB 组件内）\n")
        append("  receiver  BootReceiver / PackageInstallReceiver / DozeBackstopReceiver\n")
        append("  activity  MainActivity（状态与自检）/ SetupActivity（引导）\n")
    }

    fun storage(): String = buildString {
        append("持久化（全部经 StateFiles.writeAtomic）\n")
        append("  " + ROOT_SYS + "/<kind>/<name>/<version> + CURRENT     商店件（包与设施）\n")
        append("  " + ROOT_PROGRAMS + "/<id>/<version> + CURRENT          程序（OTA 通道）\n")
        append("  " + ROOT_USR + "/{bin,lib,current,ca-bundle.pem}     \$PREFIX 与链接农场\n")
        append("  " + ROOT_OS + "/{sessions,journal,diag,registry,permission-ledger}.json  内核状态\n")
        append("  " + ROOT_SUPERVISOR + "  进程与账号\n")
        append("  " + ROOT_ADB + "  ADB 组件工作区\n")
    }

    fun api(): String = buildString {
        append("对外 API（" + METHODS + " 个方法，JSON-RPC 2.0，UDS 逐会话）\n")
        append("  " + DEV_API + "  面向开发者写程序：运行时/包/目录/设施/实例/日志/端口/状态\n")
        append("  " + SYS_API + "  面向宿主与面板：能力/权限/锚/自动化/截屏/shell/fs/ui/设备\n")
        append("  三个正交维度：能力组(dev|sys) × 作用域(self|program|system) × 幂等性(readonly|idempotent|mutating)\n")
        append("  sys.api 一次返回完整规范：命名空间、域清单、错误码、规则、每方法的分层与弃用关系\n")
    }

    fun reconcile(): String = buildString {
        append("已对平\n")
        append("  能力组 token 单源：catalog 声明 5 个，桥里字面量同集\n")
        append("  方法名无重复；nativeAssets 单一入口；自动供给已停；前缀就位判据为动态\n")
        append("  权限角色 8 项全部有对应 PermissionCatalog 条目，无悬空\n")
        append("\n未对平（按严重度）\n")
        append("  1 两套安装布局并存：sys/ 与 programs/ 各有 registry、各有 CURRENT、各有 upgrade/rollback\n")
        append("  2 CURRENT 指针四个写者：BootReconciler / ProgramInstaller / ProgramManager / ProgramOtaStateStore\n")
        append("  3 版本比较三处实现且语义不同：VersionRange（x.y.z 整数）vs ProgramOtaVersions（token 流）\n")
        append("     实测 24.21.0-rc1：前者判相等，后者判更新 —— 同一版本号两条链路结论相反\n")
        append("  4 桥接审计覆盖 28/72：44 个写操作不落审计\n")
        append("  5 node 双来源：商店设施优先、APK 资产兜底（兜底路径已无产物，属残留）\n")
        append("  6 弃用别名 24 条指向 lobos.sys.*，逐条需确认层级归属是否符合语义\n")
    }

    fun render(): String = buildString {
        append("LobOS 运行结构（自代码对账生成）\n")
        append("规模：" + KT_FILES + " 个 .kt / " + KT_LINES + " 行；" + COMPONENTS + " 个 Android 组件；" + METHODS + " 个桥接方法\n")
        append('\n').append(layers())
        append('\n').append(components())
        append('\n').append(storage())
        append('\n').append(api())
        append('\n').append(reconcile())
    }
}