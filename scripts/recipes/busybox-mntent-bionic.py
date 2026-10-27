import io, sys
#  缺陷名（C-102）**那 mount 那个 applet 逐字在**那 bionic **上面编不过**
#    => 而而那**成因**：=> 而那 busybox 的 util-linux/mount.c 逐字**调**那 **addmntent** 与那 **endmntent**、
#      => 而而那**它们**逐字**要**那 <mntent.h>、=> 而而那 bionic 逐字**有**那**一个**头**
#        **但没有**那**两个**函数（=> 而而那**它们**是**那 glibc** 才**有**的**）。
#    => 而而那**后果**逐字 = 而那**错**逐字 =
#      那「util-linux/mount.c:810:4: error: call to undeclared function 'addmntent'」。
#    => 而那**处置**：=> 而那**上游**那一处逐字**补上**那 bionic **的**那**分支**
#      （=> 而而那**它**们**逐字是**那**两**件**事**：「那上游**是**什么**」与「那 bionic **有**什么**」）。
#      => 而那**补法**：=> 而那**那两**个**函数**逐字**改**成**那空**实现**（=> 而而那 mtab **那一族**
#      在**那设备**上逐字**没有**那个**用法**、=> 而而那**它**们**逐字是**那**两**件**事**）。
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
marker = '#include <mntent.h>'
assert marker in s, '那 mount.c 逐字**没有**那 mntent 那一行'
if 'lobos_bionic_no_mntent' in s:
    print('  [busybox] 那 mount.c 逐字**已经**补过**了**')
    sys.exit(0)
stub = (
    marker +
    chr(10) +
    '/* 缺陷名（C-102）那 bionic 逐字**没有**那 addmntent（=> 而而那 endmntent 逐字**有**）。' +
    chr(10) +
    '   => 而而那**它们**逐字是**那 glibc** 才**有**的**、=> 而而那**后果**逐字 = 而那 mount 那个 applet 逐字**编不过**。' +
    chr(10) +
    '   => 而那**处置**：=> 而那**两个**函数逐字**给**那**空**实现**（=> 而而那 mtab 那一族' +
    chr(10) +
    '     在**那设备**上逐字**没有**那个**用法**）。 */' +
    chr(10) +
    '#ifdef __BIONIC__' +
    chr(10) +
    '#define lobos_bionic_no_mntent 1' +
    chr(10) +
    'static inline int addmntent(FILE *f, const struct mntent *e) { (void)f; (void)e; return 0; }' +
    chr(10) +
    '#endif' +
    chr(10)
)
s = s.replace(marker, stub, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('  [busybox] 补了那 mount.c 的 mntent 空实现（=> 而那 bionic 逐字**没有**那 addmntent）')
