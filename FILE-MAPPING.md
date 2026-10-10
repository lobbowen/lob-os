# 文件归属建议表

93 个 Kotlin 文件 → 目标层。**这是建议，未动任何代码。**

## 分层结果

| `services/supply` | 22 |
| `services/app` | 9 |
| `services/reg` | 9 |
| `services/host` | 8 |
| `services/log` | 8 |
| `services/supervise` | 7 |
| `kernel/device` | 5 |
| `kernel/ipc` | 4 |
| `api/bridge` | 3 |
| `kernel/power` | 3 |
| `kernel/proc` | 3 |
| `services/perm` | 3 |
| `kernel/crypto` | 2 |
| `kernel/elf` | 2 |
| `kernel/layout` | 2 |
| `api/` | 1 |
| `kernel/fs` | 1 |
| `kernel/security` | 1 |

## 明细

### `api/`  (1 个)

- `ui/StatusTileService.kt`

### `api/bridge`  (3 个)

- `bridge/ApiSpec.kt`
- `bridge/CapabilityBroker.kt`
- `bridge/ScreenCaptureService.kt`

### `kernel/crypto`  (2 个)

- `os/ProgramPackageVerifier.kt`
- `runtime/SupplyProvisioner.kt`

### `kernel/device`  (5 个)

- `capability/DeviceOwnerProbe.kt`
- `capability/OsNotificationListenerService.kt`
- `capability/ScreenCaptureController.kt`
- `permissions/NotificationStore.kt`
- `permissions/PermissionCenter.kt`

### `kernel/elf`  (2 个)

- `os/ElfFacts.kt`
- `runtime/ExecBits.kt`

### `kernel/fs`  (1 个)

- `os/StateFiles.kt`

### `kernel/ipc`  (4 个)

- `lifecycle/AccessibilityServiceState.kt`
- `lifecycle/OsAccessibilityService.kt`
- `runtime/LocalExec.kt`
- `runtime/PtySession.kt`

### `kernel/layout`  (2 个)

- `os/SystemDirs.kt`
- `runtime/PrefixProvisioner.kt`

### `kernel/power`  (3 个)

- `lifecycle/DozeBackstopReceiver.kt`
- `os/DozeBackstop.kt`
- `os/PowerLocks.kt`

### `kernel/proc`  (3 个)

- `os/ProcessLedger.kt`
- `os/SessionRegistry.kt`
- `runtime/ProcessSupervisor.kt`

### `kernel/security`  (1 个)

- `os/PathGuard.kt`

### `services/app`  (9 个)

- `quickapp/DesktopIcons.kt`
- `quickapp/Foreground.kt`
- `quickapp/LobosBridge.kt`
- `quickapp/ProgramGroup.kt`
- `quickapp/QuickAppBinder.kt`
- `quickapp/QuickAppHost.kt`
- `quickapp/QuickAppLaunchActivity.kt`
- `quickapp/QuickAppPackage.kt`
- `quickapp/QuickAppRegistry.kt`

### `services/host`  (8 个)

- `lifecycle/BootReceiver.kt`
- `lifecycle/OsHostService.kt`
- `lifecycle/ResidencyPolicy.kt`
- `os/BootReconciler.kt`
- `os/OsInit.kt`
- `os/OsState.kt`
- `os/ResidencyStatus.kt`
- `OsApplication.kt`

### `services/log`  (8 个)

- `lifecycle/ResidencyAudit.kt`
- `log/Exporter.kt`
- `log/Journal.kt`
- `log/KillAudit.kt`
- `log/Level.kt`
- `os/ProgramNotification.kt`
- `os/TaskRegistry.kt`
- `RuntimeDiagnostics.kt`

### `services/perm`  (3 个)

- `permissions/PermissionCatalog.kt`
- `permissions/PermissionLedger.kt`
- `permissions/PermissionRoles.kt`

### `services/reg`  (9 个)

- `os/ManifestSchema.kt`
- `os/PortBroker.kt`
- `os/ProgramIndex.kt`
- `os/ProgramManager.kt`
- `os/ProgramRegistry.kt`
- `os/ProgramStatus.kt`
- `os/RuntimeEnvironment.kt`
- `os/UnitState.kt`
- `runtime/InstalledRuntime.kt`

### `services/supervise`  (7 个)

- `os/Backoff.kt`
- `os/ProgramSettings.kt`
- `os/UnitJobs.kt`
- `runtime/GuestAdapter.kt`
- `runtime/InstanceHost.kt`
- `runtime/SupervisorPolicy.kt`
- `runtime/SupervisorPool.kt`

### `services/supply`  (22 个)

- `lifecycle/PackageInstallReceiver.kt`
- `os/CatalogClient.kt`
- `os/PackageInstaller.kt`
- `os/PieceScan.kt`
- `os/ProgramDir.kt`
- `os/RegistryStore.kt`
- `os/VersionRange.kt`
- `ota/OtaPolicy.kt`
- `ota/ProgramArchive.kt`
- `ota/ProgramInstaller.kt`
- `ota/ProgramInstallPipeline.kt`
- `ota/ProgramOtaResolution.kt`
- `ota/ProgramOtaSelfCheck.kt`
- `ota/ProgramOtaStateStore.kt`
- `ota/ProgramOtaUpdater.kt`
- `ota/ProgramOtaVersions.kt`
- `ota/ResumableDownloader.kt`
- `ota/SelfCheckReport.kt`
- `pieces/CompatSemantics.kt`
- `pieces/DriverRegistry.kt`
- `pieces/PieceProvisioner.kt`
- `runtime/PieceUpdater.kt`
