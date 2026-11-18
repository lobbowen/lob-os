# 应用商店建设方案

本文定义 Agent OS 应用商店的方案。这是当前系统最关键的缺失：没有它，
所有选装件（python 多版本 / node / go 等）都没有进入系统的路径。

---

## 1 问题根源

系统里一直有两类加载：

| 类别 | 交付方式 | 举例 |
|---|---|---|
| 基础环境 | 随 APK 预装 | 核心库 + 9 个核心件（bash/busybox/crypto/curl/git/jq/openssl/ripgrep/zlib） |
| 选装件 | 应用商店按需下载安装 | python 3.14/3.12/3.11、node、go 等 |

之前反复出问题（python 件编好了却不知道怎么进设备），根因是：**选装件该走商店，但商店没做**。
于是我错误地试图把 python 塞进 build-apk（预装链路），而 python 本来就不该预装——
它有很多版本，需要用户选装、多版本共存、选择激活。

应用商店是这套系统从"一堆预装工具"变成"可扩展生态"的关键。

---

## 2 现状：引擎已备，缺店面

商店的后端能力在 `services/supply/` 已基本齐全：

| 能力 | 组件 | 状态 |
|---|---|---|
| 取清单 | CatalogClient.entries() / entryFor() | 有 |
| 下载（断点） | ResumableDownloader | 有 |
| 安装 | PackageInstaller / ProgramInstallPipeline | 有 |
| 进度 | InstallProgress（DOWNLOAD/VERIFY/LAND/LINK） | 有 |
| 版本管理 | PieceEntryPointer / ProgramDir / PieceUpdater | 有 |
| 多版本激活 | PieceEntryPointer（CURRENT 指针） | 有 |
| 依赖自动装 | ProgramInstallPipeline | 有 |
| 清单签名校验 | SupplyProvisioner / component-public.pem | 有 |
| 安装详情页 | SystemInstallActivity | 有雏形，但工程式 |

**缺的是**：
1. 商店主界面（可浏览/搜索/分类的选装件列表）
2. 底部导航的"商店"入口
3. 产品化的交互（列表项、安装按钮、进度、版本选择、打开/激活）
4. 与现有安装引擎的串接

---

## 3 商店要做的事（产品视角）

1. **浏览**：从 CatalogClient 取 component 清单，展示可安装件（名称、版本、大小、简介、分类）
2. **安装**：点击安装 → 走 PackageInstaller → 显示 InstallProgress 四阶段进度
3. **多版本**：同一件（如 python）可装 3.14/3.12/3.11，装后可选择"激活哪个版本"
4. **管理**：已装件可查看、切换版本、卸载
5. **状态**：显示哪些已装、哪些可更新

这与标准应用商店（apt/GUI、Play Store）逻辑一致，不是自造。

---

## 4 与三层结构的关系

- 商店是"应用程序层"与"功能组件层"之间的桥梁：用户通过商店安装功能组件（运行时/工具）
- 基础环境的库与组件仍随 APK 预装，不经过商店
- python 这类运行时通过商店安装后，作为功能组件被 agent 使用

---

## 5 实施步骤

1. 规范落地：本文 + UI 标准补充商店页面规范
2. 商店主界面：新增 SystemStoreActivity，产品化列表（分类/搜索/卡片）
3. 底部导航加"商店"入口
4. 详情页升级：SystemInstallActivity 改造成产品化详情页（版本选择/安装/进度/激活）
5. 串接引擎：CatalogClient 列表 → PackageInstaller 安装 → PieceEntryPointer 激活
6. 设备验证：商店里能看到 python → 安装 → 多版本 → 激活 → agent 能跑

---

## 6 边界与已知

- 商店的清单来自对象存储 channel.json（lobcdn.zll.ink，canary 通道），签名校验已有
- 发布件到对象存储是发布轮的事（component-canary-N ref + Qiniu），与商店 UI 独立
- 当前设备是干净状态：9 件预装就位，python 未装（因无商店）——符合预期，不是故障
- 从 adb shell 直连件报 libz.so.1 找不到，是 shell 无 LD_LIBRARY_PATH（历史红线），
  系统自己起件会带正确环境，不是件坏了

---

## 7 之前的弯路（留作警戒）

- 反复试图把 python 编进 build-apk / 改发布轮，都是因为没认清"python 是选装件、该走商店"
- python 件的技术构建（C 扩展依赖、ctypes 软链、libffi 等）已完成且验证可用，
  这些工作没有白费——商店做好后，这件直接就能作为选装件发布
