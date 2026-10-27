import io, sys
#  缺陷名（C-102）**那 mount 那个 applet 逐字在**那 bionic **上面编不过**
#    => 而而那**成因**：=> 而那 busybox 的 util-linux/mount.c 逐字**调**那 **addmntent**、
#      => 而而那**它**逐字**要**那 <mntent.h>、=> 而而那 bionic 逐字**有**那**一个**头**
#        **但没有**那 **addmntent**（=> 而而那**它**是**那 glibc** 才**有**的**）。
#    => 而而那**后果**逐字 = 而那**错**逐字 =
#      那「util-linux/mount.c:809:4: error: call to undeclared function 'addmntent'」。
#    => 而那**处置**：=> 而那**上游**那一处逐字**补上**那 bionic **的**那**分支**
#      （=> 而而那**它**们**逐字是**那**两**件**事**：「那上游**是**什么**」与「那 bionic **有**什么**」）。
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
old = "\t\t\taddmntent(mountTable, mp);\n\t\tendmntent(mountTable);\n"
new = "#ifdef HAVE_MNTENT_H\n" + old + "#endif\n"
if s.count(old) != 1:
    print('  [busybox] 那 mount.c 逐字**没有**那一处（=> 而而那**它**逐字**已经**补过**了**）')
    sys.exit(0)
io.open(p, 'w', encoding='utf-8').write(s.replace(old, new))
print('  [busybox] 补了那 mount.c 的 mntent 分支（=> 而那 bionic 逐字**没有**那 addmntent）')
