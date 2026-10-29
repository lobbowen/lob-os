#!/usr/bin/env python3
# 核那 Shape 那**五**档逐字**各答**什么（=> 而那 PE 那一档逐字**测**了**没有**）。
#
# 这一份逐字**复现**那 ExecEntry.detect **那一族**的**判据**（=> 而那 Kotlin **那一族**逐字**没有** kotlinc **在**本机）。
#   => 而那**判据**逐字**是**那 ExecEntry.kt 逐字那 **:22** 到那 :29 那**几**行 **与**那 **:57** 到那 :62 那**几**行。
#
# 用法：check-exec-shape-tiers.py
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
sys.exit(0 if fail == 0 else 1)
