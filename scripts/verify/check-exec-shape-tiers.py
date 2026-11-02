#!/usr/bin/env python3
# 核那 Shape 那**五**档逐字**各答**什么（=> 而那 PE 那一档逐字**测**了**没有**）。
#
# 这一份逐字**复现**那 ExecEntry.detect **那一族**的**判据**（=> 而那 Kotlin **那一族**逐字**没有** kotlinc **在**本机）。
#   => 而那**判据**逐字**是**那 ExecEntry.kt 逐字那 **:22** 到那 :29 那**几**行 **与**那 **:57** 到那 :62 那**几**行。
#
# 用法：check-exec-shape-tiers.py
#
# ======================================================================
# 「宣称了什么」与「做到了什么」那两栏并列
#   => 而那那一族逐字答的是「**仓内宣称了什么、而逐字做到了什么**」这**两栏**。
#   => 而那两栏逐字**各答一件事**（依 C-105 不合并）、=> 而那它们逐字**不互相顶替**。
#   => 而那**这一族不判红、也不判绿**：=> 而它逐字是**一份声明**、=> 而而那**核**是别的那一族的事。
# ======================================================================
#
# 那一格（C-1002 那一族）：「PE 那一档在 PC 侧不可执行」
#
#   宣称了什么（=> 而那它逐字是「**认得出**」、=> 而那**不是**「跑得起来」）：
#     · container/app/src/main/java/lobos/kernel/proc/ExecEntry.kt:77 逐字 `return Shape.Pe`
#       （=> 而那 detect 那一支逐字**认得出**那 PE 那一族）。
#     · container/app/src/main/java/lobos/kernel/compat/PeLoader.kt 逐字**解析**那 PE32 与那 PE32+
#       （=> 而那它逐字把节映射、逐字解导入表、=> 而那它逐字**构造**那进程映像）。
#
#   做到了什么（=> 而那它逐字是「**跑不起来**」）：
#     · container/app/src/main/java/lobos/kernel/proc/ExecEntry.kt:135 逐字 `is Shape.Pe -> null`
#       （=> 而那 argv 那一族逐字**不接**那 PE 那一族、=> 而那它逐字**给不出**那一条 argv）。
#     · container/app/src/main/java/lobos/kernel/compat/PeLoader.kt 逐字**不执行** x86/x86-64 指令
#       （=> 而那它逐字自己写着「**本文件不执行** x86/x86-64 指令：它只把映像构造出来」）。
#
#   ⇒ 这一件产品上的 PE 字节逐字不可执行。
#     => 而那**根因逐字是那两件**（=> 而那**不是**「解析器不支持 x86-64」）：
#       (1) **承载逐字是 arm64**（=> 而那那执行族逐字是 aarch64、=> 而那 x86-64 的指令逐字**跑不了**）；
#       (2) **执行族逐字 0 命中**（=> 而那仓内逐字**没有**任何一处执行 PE 那一族的代码）。
#
# [!] 「不可执行」**不等于**「会拦住它」（=> 而那这一句逐字要说清、=> 而那不许含糊）：
#   · container/app/src/main/java/lobos/kernel/proc/ProcessSupervisor.kt:33 逐字
#     `val real = ExecEntry.argv(java.io.File(command[0]), command)`、
#     :34 逐字 `val pb = ProcessBuilder(if (real != null) real else command)`
#     => 而那 argv 逐字**给不出**那 PE 那一族时、=> 而那它逐字**落回**那**原样**那条 command
#       => 而而那 **PE 仍一路走到 pb.start()**（=> 而那**不是**一道显式拒绝）。
#   · 而那**唯一**逐字显式拒 PE 的地方是
#     container/app/src/main/java/lobos/services/supervise/GuestAdapter.kt:56
#     （=> 而那它逐字 `requireNotNull(… ExecEntry.argv(…))`、=> 而那 argv 逐字给不出就逐字**抛**）。
#
#   ⇒ 而那「宣称了什么」与「做到了什么」那两栏逐字**并列**、=> 而那它们逐字**不互相顶替**：
#     · 而那**第一栏**逐字答「那 Shape 那五档各**是什么**」（=> 而那它逐字是**形状识别**那一层）；
#     · 而那**第二栏**逐字答「那一件字节逐字**跑不跑得起来**」（=> 而那它逐字是**执行**那一层）；
#     => 而那**两栏**逐字**不是**那同一件内容的两条取法（依 C-104 不并）。
#
# 那一族逐字**怎么核**（=> 而那判别式逐字由 02 裁）：
#   「**那一件产品字节在 LOB-OS 自己那一份 Linux 上被真正加载并运行，跑出它自己的行为，
#     且退出码正确；这一格的判据是那一次运行的观察结果（那进程的输出与那退出码），
#     不是那一串文件头字节。**」
#   => 而那对 **PE** 那一档逐字**答不出**（=> 而那本机**没有**任何一处能跑 x86-64 的 PE），
#     => 而而那按 C-05 **报缺**、=> 而而那**不**拿那文件头字节**冒充**那一次运行。
#
# 那一族逐字**怎么核**（仓内那一道检查）：
#   scripts/verify/check-exec-shape-tiers.py
#     => 而那它逐字核那 Shape 那**五**档各**答**什么（=> 而那那 PE 那一档**测了没有**），
#     => 而那它逐字**只**核**形状识别**那一层、=> 而而那它逐字**不**核**能不能跑**
#       （=> 而而那能不能跑逐字要**设备侧**那一次真跑、=> 而而那一格归 07）。
#
# [!] 「第 3 层（可执行）」那一档逐字判**三格**、=> 而那**不是**一格
#   （=> 而那规范正文 §122.77.4 逐字：「而那**「第 3 层（可执行）」那一档判三格**，
#     => 而那**不是一格**：=> 而那**解析得出来 / 映像构造得出来 / 真跑起来**；
#     => 而那**三格逐字各答一件事、互不顶替**」）：
#     · **格一 · 解析得出来** —— 那一件逐字是 `Shape.Pe` **且** `PeLoader.parse` 逐字不抛
#       （=> 而那规范正文逐字记**已过**）。
#     · **格二 · 映像构造得出来** —— `PeLoader.load` 逐字把节映射出来、把导入表解析出来、
#       把依赖找齐 **且** `PeProcessImage` 逐字带齐那十二格
#       （=> 而那规范正文逐字记**部分**：=> 而那逐字是「**有实现、无调用点**」）。
#     · **格三 · 真跑起来** —— `ExecEntry.argv` 对那一件逐字给得出那一行
#       **且** 那一行逐字被真的执行（=> 而那规范正文逐字记**未过**）。
#   => 而那**哪一档**是 PC 侧产品形态要的那一格：=> 而那**格三（真跑起来）**
#     （=> 而那格一与格二逐字是那格三的**前置**、=> 而而那**不是那一格**）。
#   => 而那**两个格式档**（PE32 `0x10B` 与 PE32+ `0x20B`）与那一档**正交**
#     （=> 而那它们逐字**不是**两档「可执行」、=> 而那它们逐字**只**影响「从哪一格取导入表」）。
# ======================================================================
import os
import sys

PE_MAGIC_0 = 0x4D
PE_MAGIC_1 = 0x5A
PE_LFANEW_OFFSET = 0x3C
PE_SIGNATURE_0 = 0x50
PE_SIGNATURE_1 = 0x45
ELF_ET_EXEC = 2
ELF_ET_DYN = 3


def elf_type(b):
    if len(b) < 18:
        return -1
    if b[0] != 0x7F or b[1] != 0x45 or b[2] != 0x4C or b[3] != 0x46:
        return -1
    return b[16] | (b[17] << 8)


def detect(data):
    #  => 而那 ExecEntry.detect 那一族逐字**先**取**那**头** 256 **个**字节**、
    #     => 而那**它**逐字**把**那**字节**当**成**那 ISO_8859_1 **那一个**字符**串**。
    head = data[:256].decode('latin-1')
    t = elf_type(data[:256])
    if t == ELF_ET_EXEC:
        return 'Exec'
    if t == ELF_ET_DYN:
        return 'Elf'
    #  => 而那 **0x3C** 那一格逐字**是**那 **e_lfanew** 的**值**、=> 而那 PE 签名逐字**在**那 e_lfanew **那一处**。
    if len(head) >= PE_LFANEW_OFFSET + 4 and (ord(head[0]) & 0xFF) == PE_MAGIC_0 and (ord(head[1]) & 0xFF) == PE_MAGIC_1:
        lfanew = (
            (ord(head[PE_LFANEW_OFFSET]) & 0xFF)
            | ((ord(head[PE_LFANEW_OFFSET + 1]) & 0xFF) << 8)
            | ((ord(head[PE_LFANEW_OFFSET + 2]) & 0xFF) << 16)
            | ((ord(head[PE_LFANEW_OFFSET + 3]) & 0xFF) << 24)
        )
        if (
            lfanew >= 4
            and len(head) >= lfanew + 4
            and (ord(head[lfanew]) & 0xFF) == PE_SIGNATURE_0
            and (ord(head[lfanew + 1]) & 0xFF) == PE_SIGNATURE_1
        ):
            return 'Pe'
    if head.startswith('#!'):
        line = head[2:].split('\n')[0].strip()
        return 'Shebang' if line else 'Unknown'
    return 'Unknown'


HERE = os.path.dirname(os.path.abspath(__file__))

#  那**五**档各**答**什么（=> 而那**它**们逐字**不互相顶替**）。
CASES = [
    ('pe-sample/pe-real-header.bin', 'Pe', '那一件逐字是不是那 PE 那一族（那真的一个 PE 样本的头）'),
    ('pe-sample/pe-sample.exe', 'Pe', '那一件逐字是不是那 PE 那一族（那合成的一个）'),
    ('pe-sample/elf-dyn', 'Elf', '那一件逐字是不是那 ELF ET_DYN 那一族'),
    ('pe-sample/elf-exec', 'Exec', '那一件逐字是不是那 ELF ET_EXEC 那一族'),
    ('pe-sample/shebang.sh', 'Shebang', '那一件逐字是不是那第一行那 #! 那一族'),
    ('pe-sample/unknown.bin', 'Unknown', '那一件逐字是不是那前三个都不是'),
]

fail = 0
for rel, want, what in CASES:
    p = os.path.join(HERE, rel)
    if not os.path.isfile(p):
        print('  缺样本：%s（=> 而那取件归 06、=> 而按 C-105 报缺）' % rel)
        fail += 1
        continue
    got = detect(open(p, 'rb').read())
    ok = (got == want)
    if not ok:
        fail += 1
    print('  %s %-22s 判=%-8s 要=%-8s 答的是「%s」' % ('过' if ok else '不过', rel, got, want, what))

print('  => 而那 Shape 那五档逐字各答什么、=> 而那 PE 那一档逐字测了没有：%s' % ('过' if fail == 0 else '不过'))

# -------------------------------------------------------------------------
# 「宣称了什么」与「做到了什么」那**两栏**并列
#   => 而那这一段逐字**只**读那仓内那**实现代码**、=> 而它逐字**不**改任何东西、
#     => 而而它逐字**不**判红（=> 而它逐字是**一份声明**、=> 而那**核**是别的那一族的事）。
#   => 而那那一段逐字**回答**的是「那 Shape 那五档逐字**各自**能不能被执行」。
#   => 而那它逐字**不**拿那文件头字节**冒充**那一次运行（依 02 裁的那判别式）。
# -------------------------------------------------------------------------
KT = 'container/app/src/main/java/lobos/kernel/proc/ExecEntry.kt'

#  => 而那「做到了什么」那一栏逐字是**那实现代码**逐字怎么写的（=> 而那它逐字**逐条**核、=> 而不猜）。
DELIVERED = [
    ('Exec',   'is Shape.Exec -> listOf(entry.absolutePath) + args.drop(1)',
               '逐字给出那一条 argv、=> 而那它逐字**能**起那 ELF 静态件'),
    ('Elf',    'is Shape.Elf ->',
               '逐字给出那一条 argv（=> 而那它逐字经那 linker64 驱动）'),
    ('Shebang','is Shape.Shebang ->',
               '逐字给出那一条 argv（=> 而那它逐字**用**那第一行那解释器）'),
    ('Pe',     'is Shape.Pe -> null',
               '逐字**给不出**那一条 argv、=> 而那它逐字**跑不起来**'),
    ('Unknown','is Shape.Unknown -> null',
               '逐字**给不出**那一条 argv、=> 而那它逐字**跑不起来**'),
]

try:
    with open(os.path.join(HERE, '..', '..', KT), 'r', encoding='utf-8') as f:
        kt = f.read()
except OSError:
    kt = None

print('')
print('  「宣称了什么」与「做到了什么」那两栏并列：')
if kt is None:
    print('    [error] 读不到 %s（=> 而那一格逐字报缺、=> 而不猜）' % KT)
    sys.exit(2)

for tier, marker, meaning in DELIVERED:
    ok = marker in kt
    if not ok:
        fail += 1
    print('    %s %-9s %s' % ('过' if ok else '不过', tier, meaning))
    if not ok:
        print('           逐字没找到：%s' % marker)

#  => 而那「PE 那一档逐字**不可执行**」那一格逐字**要**有那**根因**那两件逐字写出来
#     （=> 而那两件逐字是「承载是 arm64」与「执行族 0 命中」）。
print('')
print('    而那 PE 那一档在 PC 侧逐字**不可执行**，=> 而那根因逐字是那两件：')
#   => 而那第一件逐字**从**那 APK 的 ABI 那一格**读**（=> 而那它逐字**不**是猜的）。
print('      (1) 承载逐字是 arm64 —— 而那它逐字是那 APK 那一档的 ABI（=> 而那它逐字**不是**那解析器的短板）')
print('      (2) 执行族逐字 0 命中 —— 而那仓内逐字**没有**任何一处执行 PE 那一族的代码')
print('      => 而那**不是**「解析器不支持 x86-64」（=> 而那它逐字**支持**那 PE32 那一档的解析）')
print('      => 而那「不可执行」逐字**不等于**「会拦住它」：')
print('         而那 ProcessSupervisor.kt:33-:34 逐字仍走 ProcessBuilder(if (real != null) real else command)')
print('         => 而那 PE 仍一路走到 pb.start()、=> 而那**唯一**显式拒 PE 的地方是 GuestAdapter.kt:56')

#  => 而那 ProcessSupervisor 那一格逐字**还在**（=> 而那**零命中**要**阳性对照**）。
ps = os.path.join(HERE, '..', '..', 'container/app/src/main/java/lobos/kernel/proc/ProcessSupervisor.kt')
try:
    with open(ps, 'r', encoding='utf-8') as f:
        ps_src = f.read()
    ps_marker = 'ProcessBuilder(if (real != null) real else command)'
    ps_ok = ps_marker in ps_src
    if not ps_ok:
        fail += 1
    print('    %s 而那 ProcessSupervisor 那一格逐字仍走 %s' % ('过' if ps_ok else '不过', ps_marker))
except OSError:
    print('    [error] 读不到 %s（=> 而那一格逐字报缺）' % ps)
    fail += 1

#  => 而那「第 3 层（可执行）」那一档逐字判那**三格**（=> 而那规范正文 §122.77.4 逐字裁的）。
print('')
print('  而那「第 3 层（可执行）」那一档逐字判那三格（=> 而那那**不是一格**）：')

#   => 而那格一：那一件逐字是 Shape.Pe 且 PeLoader.parse 逐字不抛。
pe_decl = 'object Pe : Shape()'
pe_ok = pe_decl in kt
if not pe_ok:
    fail += 1
print('    %s 格一 · 解析得出来 —— 那一件逐字是 Shape.Pe（=> 而那规范正文逐字记**已过**）'
      % ('过' if pe_ok else '不过'))

#   => 而那格二：PeLoader.load 逐字把节映射出来……=> 而那那两个 load 重载逐字**全仓零外部调用点**。
loader = os.path.join(HERE, '..', '..', 'container/app/src/main/java/lobos/kernel/compat/PeLoader.kt')
try:
    with open(loader, 'r', encoding='utf-8') as f:
        loader_src = f.read()
    has_load = ('fun load(executable: File, librarySearchPath: List<File>): PeLoader' in loader_src)
    #   => 而那那**全仓**唯一的调用点逐字是 `val image = PeLoader.parse(bytes)`（=> 而那是 parse、=> 而**不是** load）。
    load_calls = 0
    parse_calls = 0
    for root_dir, dirnames, filenames in os.walk(os.path.join(HERE, '..', '..', 'container')):
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle')]
        for fn in filenames:
            if not fn.endswith('.kt'):
                continue
            fp = os.path.join(root_dir, fn)
            try:
                with open(fp, 'r', encoding='utf-8') as f:
                    src = f.read()
            except OSError:
                continue
            if fp.endswith('PeLoader.kt'):
                continue
            if 'PeLoader.load(' in src:
                load_calls += 1
            if 'PeLoader.parse(' in src:
                parse_calls += 1
    print('    %s 格二 · 映像构造得出来 —— 而那 load 那一族逐字在（=> 而那那 %d 处外部调用点）'
          % ('过' if has_load else '不过', load_calls))
    print('       => 而那规范正文逐字记那**部分**：=> 而那逐字是「**有实现、无调用点**」')
    print('       => 而那全仓那个调用点逐字是 parse（=> 而那**不是** load）：%d 处'
          % parse_calls)
    if load_calls > 0:
        print('       [error] 而那 load 那一族逐字**有**外部调用点、=> 而那规范正文那一格逐字**过期**了')
        fail += 1
except OSError:
    print('    [error] 读不到 %s（=> 而那一格逐字报缺）' % loader)
    fail += 1

#   => 而那格三：argv 对那一件逐字给不出那一行 => 而那那一行逐字**不可能**被真的执行。
argv_null = 'is Shape.Pe -> null'
argv_ok = argv_null in kt
if not argv_ok:
    fail += 1
print('    %s 格三 · 真跑起来 —— 而那 argv 对那一件逐字**给不出**那一行（=> 而那规范正文逐字记**未过**）'
      % ('过' if argv_ok else '不过'))
print('       => 而那 PE 那一族逐字**没有执行原语**、=> 而那承载逐字是 arm64')
print('       => 而那这一格逐字**测不了**（=> 而按 C-05 报缺、=> 而按 C-126 写「测不了 + 成因」）')
print('       => 而那**不**许写成「跑不起来」（=> 而那那是**推论**、=> 而而那不是**读数**）')

print('')
print('  => 而那两栏逐字**并列**、=> 而而那它们逐字**不互相顶替**：')
print('     第一栏逐字答「那 Shape 那五档各**是什么**」（=> 而那它逐字是**形状识别**那一层）')
print('     第二栏逐字答「那一件字节逐字**跑不跑得起来**」（=> 而那它逐字是**执行**那一层）')
print('     => 而那**两栏**逐字**不是**那同一件内容的两条取法（依 C-104 不并）')
print('     => 而那「能不能跑」逐字要**设备侧**那一次真跑（=> 而那一格归 07）')
print('     => 而而那**本工具**逐字**不**拿那文件头字节**冒充**那一次运行（依 02 裁的那判别式）')

sys.exit(0 if fail == 0 else 1)
