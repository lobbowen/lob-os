# 共享库路径：Lob OS 在 Android 上的真实边界

## 一、Linux 怎么做的

```
装一个库 → 放进 /usr/lib
        → ldconfig 扫一遍，生成 /etc/ld.so.cache
        → 所有进程的动态链接器都读那一个文件
        → 任何程序立即能用，没人告诉它任何事

关键：glibc 的 linker 会读一个【所有进程共享的文件】。
```

## 二、我们做了什么（真机实测）

```
✓ 装件                              opt/<件>/<版本>/lib
✓ 链接关系（SONAME 软链）            usr/lib/libz.so.1 → opt/zlib/1.3.2/lib/libz.so.1.3.2
✓ 版本管理                          opt/<件>/CURRENT
✓ etc/ld.so.conf 与 etc/ld.so.cache  写了（按 Linux 的形态）
✗ 链接器不读它们
✗ 宿主进程 LD_LIBRARY_PATH = 空（它自己都找不到我们装的库）
→ 唯一生效的 LD_LIBRARY_PATH 是 treeRootEnv 逐个程序设的
```

## 三、Android 上没有「全局库注册表」

**真机实测过 linker namespace（PGU110 / Android 16）：**

```
__loader_android_init_anonymous_namespace   能取到（libdl 那层没有，只在 linker 里）
三参调用建 namespace                        成功（ns = 0x1）
unsetenv("LD_LIBRARY_PATH")
dlopen("<usr/lib>/libz.so.1")               not found
dlopen("libz.so.1")                         not found
```

**结论：namespace 能建，但它不是全局注册表。**

```
它做的事：把【几个 so】的搜索路径串起来，让彼此能找到对方
它不做的事：让一个「共享的搜索路径表」被所有进程自动读取

Linux 的 ldconfig 是后者（写一个所有进程共享的文件）
Android 的 namespace 是前者（进程内串 so）
```

**这两者不是一回事。**

## 四、结论：这是平台边界，不是设计缺陷

```
能拿到的（都已验过在真机上成立）
  · 装件、版本管理、链接关系
  · 程序继承系统的 PATH 与 LD_LIBRARY_PATH

拿不到的
  · 「装一个库，所有程序立刻能用」

所以不能说「Lob OS 是完整的 Linux 式系统」。
准确的说法是：
  Lob OS 是在 Android 硬件上、按 Linux 的分工与形态构建的系统；
  共享库路径是【逐进程传递】的，而不是由一个共享文件广播的。
```

## 五、为什么不做 wrapper 绕过去

考虑过：程序清单写 `entry=app.js`，实际起的是
`usr/bin/.lobos/wrapper/<程序>`（一个带完整环境的壳）。

**不做的理由：**

```
① 机制上是同一件事（都是逐进程传递），只是藏起来
② 与 execEntry 的方向相反
     execEntry 的意义：入口自己声明怎么跑（内核决定）
     wrapper：系统替程序决定怎么跑
③ 藏起来的东西最难查
     真出问题时会看到「为什么这个程序有那堆环境变量」而找不到答案
```

## 六、验收方法（以后要复查就跑这个）

```bash
# 宿主进程自己能不能找到我们装的库
run-as lobos.os sh -c 'echo $LD_LIBRARY_PATH'
# ↑ 应该是空 —— 这是已知边界，不是 bug

# 程序里能不能找到
run-as lobos.os sh -c 'echo $LD_LIBRARY_PATH'
# ↑ 应该在 treeRootEnv 起的作用域内含 files/usr/lib

# 跨件依赖解析
linker64 --list files/opt/curl/8.22.0/lib/libcurl.so
# ↑ libssl / libcrypto / libz.so.1 都应指向 opt/<件>/<版本>/lib
```
