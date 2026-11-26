# 快应用的战略定位（2026-11-27）

用户在"删除桥机制"讨论中明确了快应用的定位。这是应用层生态的顶层决策，
记录了为什么快应用必须保留、它和系统的关系、以及它和要删的桥的区别。

## 快应用是什么

- 快应用属于**安卓层面**，是应用层的东西，不是系统内核
- 它是我们这套生态里**唯一的前端选择**

## 为什么必须是快应用

- 我们的系统没有桌面化
- 在安卓上跑 Linux 桌面程序并让它有桌面显示，是无法突破的（或即使有办法，
  也会造成系统性能损耗等各种问题）——这是一个根本性的取舍
- 桌面级产品在 Linux 上有自己的桌面体系、图形管理、前端；我们要构建的生态，
  必须有自己的前端
- 因此生态产品的形态只能是：**后端服务在系统内，前端是快应用**
- 未来会在系统内做很多符合系统生态的应用程序，它们的前端都由快应用承担

## 快应用与要删的"桥"的区别

- 桥（CapabilityBroker / UDS / LOBOS_SESSION_TOKEN / 84 个 method / 快应用 invoke
  走 broker.invokeLocal）是**宿主控制面的遗留工程债**，整层删除
- 快应用目前借用了桥的 invoke 通道，那是历史接线，不是快应用的本质
- 删桥后，快应用重新定位为：**应用层前端，直调系统服务**（白名单方式），
  不再经过 UDS/token/capability 那套控制面机制

## 执行含义

1. 桥整层删除：CapabilityBroker / ControlClient / ControlRequestStore /
   ApiSpec / SessionRegistry / token 注入 / 宿主 OTA 的 UDS 包装
2. 宿主 update/rollback/exec：改成本地直调 services，保留幂等语义、去掉 token/socket
3. 快应用 LobosBridge.invoke：改成白名单直调 services，保留快应用作为前端的能力
4. 程序进程去掉 LOBOS_BRIDGE_SOCKET / LOBOS_SESSION_TOKEN 注入
5. 快应用生态的"打通"（系统内服务 ↔ 快应用前端）在这个基础上继续建设，
   不复用被删除的控制面桥

## 不变的边界

- dsh 是 Ubuntu 里的普通 npm 应用，与桥无关，按标准 npm 安装/启动
- 系统盘保持纯净；产品/运行时（dsh/claude/codex/python/go）由用户安装
