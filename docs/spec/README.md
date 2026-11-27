# 规范目录说明（2026-11-27）

## 当前架构规范

当前架构是单一线条：APK 内置 Ubuntu rootfs 系统盘 + fastroot 执行引擎 +
libghostty-vt 终端 + 产品由 apt/npm 标准安装。权威规范见：

- `../ENGINEERING-STANDARD.md` — 总入口
- `SYSTEM-DISK-BAKED.md` — 系统盘本体
- `TERMINAL-RESEARCH-DECISION.md` — 终端引擎决策
- `QUICKAPP-STRATEGIC-POSITION.md` — 快应用生态定位
- `../findings/PRODUCT-INSTALL-RUN-VERIFICATION.md` — 产品安装/启动验证
- `../findings/FASTROOT-INTEGRATION-STATUS.md` — fastroot 引擎验证

## 关于 ONE-STANDARD.md

`ONE-STANDARD.md` 是 V1 时代（LD_PRELOAD/VFS hook/musl/件体系/桥）的巨型历史
规范，43884 行，不在 git 里。**它不是当前有效的规范**，不再被任何校验脚本读取。
仅作为历史存档保留在本地，供需要追溯旧决策时查看；当前规范以上面列出的文件为准。

## 历史 / 诊断文档

`DEEP-ARCHITECTURE-AUDIT-2026-11-26.md`、`UI-REFACTOR-PLAN*.md`、
`UI-CONCEPTS.md`、`UI-COMPLIANCE-AUDIT.md`、`ARTIFACT-CLASSIFICATION.md`、
`COMPONENT-UNINSTALL-CONTRACT.md` 等是 V1 时代或 UI 演进的历史记录，
只读参考，不作为当前规范。
