# UI 导航条调研（2026-11-27）

用户要求：做一个导航条，但不要传统"浮在上面、两侧圆弧"的底部导航；要"像圆柱一样、整体圆弧形"的标准组件；尽量用原生组件或成熟框架。本轮只调研不动代码，供 UI/产品/交互一起定方案。

## 一句话结论

Android/Material3 原生**没有**整体圆弧/圆柱 tab bar 组件。最接近的原生件是 M3 Expressive 的 `ShortNavigationBar`（居中短条）和 `HorizontalFloatingToolbar`（悬浮圆角条），但都不是整条弧面。用户要的形态需要自定义形状/自绘，或少数小众第三方库。当前项目根本没有常驻导航条——入口是首页卡片里的一排图标。

## 当前项目 UI 现状（改造基线）

- 技术栈：Compose BOM 2026.09.00 + Material3 + Navigation 3（`NavDisplay`）
- 导航逻辑在 app 模块 `LobosNavHost.kt:41-55`，用 `backStack` 手工管理 Home/Terminal/Space/Apps/More/Check/Recovery
- **没有任何** `NavigationBar`/`BottomAppBar`/`NavigationRail`；页面切换靠首页 `HomeScreen.kt:146-158` 的一排图标（height=72dp）
- 进入子屏后没有全局导航入口，只能物理返回
- 主题在 `ui/theme/Theme.kt`（系统蓝 #0B6BCB、深墨 #111318），品牌规范 `brand/brand.md`
- ui 模块无共享形状组件，全是内联 `RoundedCornerShape`；无 pill/弧形可复用件

## 用户描述的形态辨认（需设计师确认）

| 候选 | 名称 | 特征 | 与描述匹配 |
|---|---|---|---|
| a | 悬浮胶囊 pill / iOS 26 Liquid Glass | 悬浮圆角条，两端圆弧，半透明 | 不匹配：用户明确排斥"浮在上面" |
| b | 中间凹口 concave/notched | 底部条中间缺口嵌 FAB | 不匹配：用户要"整体圆弧"非局部缺口 |
| c | 整体拱形/圆柱感 arc/curved tab bar | 整条上边或表面是连续弧线，图标沿弧排布 | **最匹配**："像圆柱一样、整体圆弧形" |
| d | Liquid Glass 材质 | 半透明折射，是材质非几何 | 单独不能解释，可能叠加在 c 上 |

参考（c 类）：60fps Curvy Bottom Tab https://60fps.design/shots/kann-curvy-bottom-tab-interaction ；expo-curved-bottom-tabs https://github.com/rit3zh/expo-curved-bottom-tabs 。
"圆柱"更可能是 2D 连续弧面的口语描述，而非某官方组件名。需设计师用参考图确认是 2D 弧面还是 3D 高光厚度。

## 原生组件能力（证据）

- `NavigationBar`/`NavigationRail`/`BottomAppBar`：容器固定矩形，token `ContainerShape=CornerNone`，无 shape 参数；圆角只在选中 indicator
- M3 Expressive 新增 `ShortNavigationBar`（居中短条）、`HorizontalFloatingToolbar`（悬浮圆角条）、`MaterialShapes`（Pill/Cookie 等可 morph）——有整体感但不是整条弧面
- 原生确认无整体圆弧/圆柱 tab bar；`modifier.clip(CustomShape)` 可裁剪但命中区/insets 仍按矩形，有副作用
- 证据：androidx NavigationBar.kt/NavigationBarTokens.kt、m3.material.io、composables.com

## 第三方库（stars/维护取样 2026-11-27）

| 库 | 情况 | 判断 |
|---|---|---|
| MehdiSekoba/AnimatedBottomBar | 18 star，Apache-2.0，Maven 1.1.0，圆角条+贝塞尔 hill 指示器，M3 兼容有 semantics | 相对可维护，但形态是"hill"非整条弧 |
| nameisjayant/compose-bottombar | 7 star，0.1.0，Dome/Crest/Wave 等变体 | 设计参考，生产风险高 |
| ibrahimsn98/SmoothBottomBar | 2067 star，MIT，胶囊条+滑动 pill | 成熟但形态不符（胶囊非圆柱） |
| Rohit-554/compose-curved-bottombar-navigation | 23 star，无 LICENSE，非可依赖库，Canvas 画 FAB 曲线 | toy，可借鉴 Path 算法 |
| XML 老库(Meow/CurvedBottomNavigation 等) | 多年停更、JitPack、Fragment 耦合 | 不建议，与 Navigation3 冲突 |

## 方案对比与建议

| 方案 | 形态匹配 | 可维护性 | 建议 |
|---|---|---|---|
| 原生 NavigationBar + clip(CustomShape) | 部分 | 中（命中区副作用） | "短胶囊"路线可用 |
| M3E ShortNavigationBar/FloatingToolbar | 部分 | 高（原生） | 若设计接受居中短条 |
| 第三方 AnimatedBottomBar 1.1.0 | hill 非整弧 | 中 | 做 B 方案（厚胶囊）可试 |
| 自绘 Canvas + NavigationBarItem | 完全可控 | 取决于团队 | **A 方案（整体弧面）首选**：当作项目内标准组件维护，配无障碍/insets 验收 |

## 需设计/PM/交互先确认的问题

1. "圆柱"是 2D 连续弧面（整条弯），还是 3D 高光厚度？
2. 贴底还是悬浮？用户已排斥悬浮，倾向贴底整体弧面
3. 全宽还是内缩短条？
4. 是否要 Liquid Glass 半透明折射（材质与几何分开定）？
5. 是否保留首页卡片内那排图标（避免与底栏重复）？

## 改造影响面（确认方案后）

- 必改：`app/LobosNavHost.kt:41-55,58-109`（Scaffold 包底栏、tab 状态与返回栈语义）、`ui/home/HomeScreen.kt:146-158`（去重复入口）
- 新增：`ui/nav/LobosNavigationBar.kt`（自定义形状组件）
- 可能改：`ui/theme/Theme.kt`（导航色 token）、`MainActivity.kt`（insets）、`docs/spec/HOME-STATUS-BAR-CONTRACT.md`
