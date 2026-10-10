# 代码结构

迁移后的实际结构（不是建议）。共 99 个文件。

## 三层

| 层 | 目录数 | 文件数 | 依赖方向 |
|---|---|---|---|
| `api/` 调用面 | 1 | 3 | → services → kernel |
| `services/` 服务层 | 6 | 68 | → kernel |
| `kernel/` 内核 | 11 | 28 | 只向下，不认识业务词汇 |

## 明细

### `api`  (3)

- `api/ApiSpec`
- `api/CapabilityBroker`
- `api/StatusTileService`

### `kernel/KernelHooks.kt`  (1)

- `kernel/KernelHooks`

### `kernel/crypto`  (2)

- `kernel/crypto/Crypto`
- `kernel/crypto/ProgramPackageVerifier`

### `kernel/device`  (8)

- `kernel/device/AccessibilityServiceState`
- `kernel/device/DeviceOwnerProbe`
- `kernel/device/NotificationStore`
- `kernel/device/OsAccessibilityService`
- `kernel/device/OsNotificationListenerService`
- `kernel/device/PermissionCenter`
- `kernel/device/ScreenCaptureController`
- `kernel/device/ScreenCaptureService`

### `kernel/elf`  (2)

- `kernel/elf/ElfFacts`
- `kernel/elf/ExecBits`

### `kernel/fs`  (1)

- `kernel/fs/StateFiles`

### `kernel/ipc`  (2)

- `kernel/ipc/LocalExec`
- `kernel/ipc/PtySession`

### `kernel/layout`  (3)

- `kernel/layout/Landed`
- `kernel/layout/PrefixProvisioner`
- `kernel/layout/SystemDirs`

### `kernel/power`  (4)

- `kernel/power/DozeBackstop`
- `kernel/power/DozeBackstopReceiver`
- `kernel/power/PowerHostHooks`
- `kernel/power/PowerLocks`

### `kernel/proc`  (3)

- `kernel/proc/ProcessLedger`
- `kernel/proc/ProcessSupervisor`
- `kernel/proc/SessionRegistry`

### `kernel/security`  (1)

- `kernel/security/PathGuard`

### `kernel/time`  (1)

- `kernel/time/Heartbeat`

### `services/app`  (12)

- `services/app/CompatSemantics`
- `services/app/DesktopIcons`
- `services/app/DriverRegistry`
- `services/app/Foreground`
- `services/app/LobosBridge`
- `services/app/PieceProvisioner`
- `services/app/ProgramGroup`
- `services/app/QuickAppBinder`
- `services/app/QuickAppHost`
- `services/app/QuickAppLaunchActivity`
- `services/app/QuickAppPackage`
- `services/app/QuickAppRegistry`

### `services/host`  (9)

- `services/host/BootReceiver`
- `services/host/BootReconciler`
- `services/host/OsApplication`
- `services/host/OsHostService`
- `services/host/OsInit`
- `services/host/OsState`
- `services/host/PackageInstallReceiver`
- `services/host/ResidencyAudit`
- `services/host/ResidencyPolicy`

### `services/log`  (5)

- `services/log/Exporter`
- `services/log/Journal`
- `services/log/KillAudit`
- `services/log/Level`
- `services/log/RuntimeDiagnostics`

### `services/perm`  (3)

- `services/perm/PermissionCatalog`
- `services/perm/PermissionLedger`
- `services/perm/PermissionRoles`

### `services/reg`  (12)

- `services/reg/InstalledRuntime`
- `services/reg/ManifestSchema`
- `services/reg/PortBroker`
- `services/reg/ProgramIndex`
- `services/reg/ProgramManager`
- `services/reg/ProgramNotification`
- `services/reg/ProgramRegistry`
- `services/reg/ProgramSettings`
- `services/reg/ProgramStatus`
- `services/reg/ResidencyStatus`
- `services/reg/RuntimeEnvironment`
- `services/reg/UnitState`

### `services/supervise`  (7)

- `services/supervise/Backoff`
- `services/supervise/GuestAdapter`
- `services/supervise/InstanceHost`
- `services/supervise/SupervisorPolicy`
- `services/supervise/SupervisorPool`
- `services/supervise/TaskRegistry`
- `services/supervise/UnitJobs`

### `services/supply`  (20)

- `services/supply/CatalogClient`
- `services/supply/OtaPolicy`
- `services/supply/PackageInstaller`
- `services/supply/PieceRegistrar`
- `services/supply/PieceScan`
- `services/supply/PieceUpdater`
- `services/supply/ProgramArchive`
- `services/supply/ProgramDir`
- `services/supply/ProgramInstallPipeline`
- `services/supply/ProgramInstaller`
- `services/supply/ProgramOtaResolution`
- `services/supply/ProgramOtaSelfCheck`
- `services/supply/ProgramOtaStateStore`
- `services/supply/ProgramOtaUpdater`
- `services/supply/ProgramOtaVersions`
- `services/supply/RegistryStore`
- `services/supply/ResumableDownloader`
- `services/supply/SelfCheckReport`
- `services/supply/SupplyProvisioner`
- `services/supply/VersionRange`
