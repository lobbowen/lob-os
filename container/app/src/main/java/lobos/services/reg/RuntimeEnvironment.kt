package lobos.services.reg

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.app.DriverRegistry
import lobos.services.app.PieceProvisioner
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.supply.CatalogClient
import lobos.services.supply.PackageInstaller
import lobos.services.supply.PieceScan
import lobos.services.supply.ShellEntry
import lobos.kernel.mm.LibPath

object RuntimeEnvironment {

    data class Snapshot(
        val prefixReady: List<String>,
        val prefixMissing: List<String>,
    ) {
        val complete: Boolean get() = prefixMissing.isEmpty()
    }

    @Volatile private var cached: Snapshot? = null

    data class TreeRoot(
        val home: File,
        val tmpDir: File,
        val nativeLibDir: String,
        val prefixRoot: File,
        val prefixBin: File,
    val prefixLib: File,
    )

    val RESERVED_ENV: Set<String> = setOf(
        "HOME", "TMPDIR", "PATH", "LANG", "SHELL", "LD_LIBRARY_PATH", "LD_PRELOAD",
        "NODE_BIN", "NODE_PATH", "NODE_OPTIONS", "SSL_CERT_DIR", "SSL_CERT_FILE",
        "CURL_CA_BUNDLE", "GIT_SSL_CAINFO", "LOBOS_COMPAT_LOG", "LOBOS_BRIDGE_SOCKET",
        "LOBOS_SESSION_TOKEN", "LOBOS_PROGRAM_ID", "LOBOS_PROGRAM_GENERATION",
        "LOBOS_ANDROID", "LOBOS_PLATFORM", "LOBOS_SUPERVISOR_HOME", "LOBOS_UI_DIR",
        "LOBOS_PERMISSION_MODE", "LOBOS_FLOCK_SO", "LOBOS_OWN_SESSION",
    )

    fun withoutReserved(declared: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val dropped = declared.keys.filter { RESERVED_ENV.contains(it) }.sorted()
        return declared.filterKeys { !RESERVED_ENV.contains(it) } to dropped
    }

    fun treeRootEnv(ctx: Context, root: TreeRoot, inheritedPath: String?, inheritedLd: String? = null): Map<String, String> = buildMap {
        put("HOME", root.home.absolutePath)
        put("TMPDIR", root.tmpDir.absolutePath)
        put("LANG", "C.UTF-8")
    put("LD_LIBRARY_PATH", joinPath(root.nativeLibDir, root.prefixLib.absolutePath, inheritedLd))
        InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.let { put("NODE_BIN", it.absolutePath) }
        put(
            "PATH",
            joinPath(
                root.prefixBin.absolutePath,
                InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.parentFile?.absolutePath,
                inheritedPath,
            )
        )
        val preload = lobos.kernel.compat.Compat.preload(ctx)
        if (preload.isNotBlank()) {
            put("LD_PRELOAD", preload)
            put("LOBOS_COMPAT_LOG", lobos.services.app.DriverRegistry.degradeLog(ctx).absolutePath)
        }
        val caDirs = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/data/misc/keychain/cacerts-added",
        ).filter { File(it).isDirectory }
        if (caDirs.isNotEmpty()) put("SSL_CERT_DIR", caDirs.joinToString(":"))
        val caBundle = PrefixProvisioner.caBundleAt(root.prefixRoot)
        if (caBundle.isFile) {
            put("SSL_CERT_FILE", caBundle.absolutePath)
            put("CURL_CA_BUNDLE", caBundle.absolutePath)
            put("GIT_SSL_CAINFO", caBundle.absolutePath)
        }
        ShellEntry.resolve(ctx)?.let { put("SHELL", it.absolutePath) }
    }

    fun joinPath(vararg parts: String?): String =
        parts.filterNotNull()
            .flatMap { it.split(File.pathSeparator) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(File.pathSeparator)

    fun treeRootFor(ctx: Context): TreeRoot = TreeRoot(
        home = ctx.filesDir,
        tmpDir = ctx.cacheDir,
        nativeLibDir = libSearchPath(ctx),
        prefixRoot = PrefixProvisioner.root(ctx),
        prefixBin = PrefixProvisioner.binDir(ctx),
        prefixLib = PrefixProvisioner.libDir(ctx),
    )

    fun libSearchPath(ctx: Context): String {
        val dirs = LinkedHashSet<String>()
        dirs.add(LibPath.searchPath(ctx))
        return dirs.filter { File(it).isDirectory }.joinToString(":")
      }

    fun ensure(ctx: Context): Snapshot {
        cached?.takeIf { it.complete }?.let { s ->
            RuntimeDiagnostics.append(
                ctx, "prefix", true, "\$PREFIX 件全部就位（本进程已装配）",
                PrefixProvisioner.root(ctx).absolutePath + " 已有=" + s.prefixReady.joinToString()
            )
            return s
        }
        return synchronized(this) {
            cached?.takeIf { it.complete }?.let { return it }
            assemble(ctx).also { if (it.complete) cached = it }
        }
    }

    private fun isLink(f: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(f.path).st_mode
        (mode and S_IFMT) == S_IFLNK
    }.getOrDefault(false)

    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000

    private fun assemble(ctx: Context): Snapshot {
        val provisioned = PrefixProvisioner.provision(ctx)
        val found = PieceScan.scan(ctx).filter { it.role != PieceScan.HEADERS }
        val landedIds = found.map { f -> f.id }.toSet()
        val packagedIds = PieceScan.packagedIds(ctx)
        val absent = packagedIds - landedIds
        val exeFiles = found
            .filter { File(it.dir, "bin").isDirectory }
            .flatMap { File(it.dir, "bin").listFiles()?.filter { f -> f.isFile } ?: emptyList() }
            .toSet()
        val missing = exeFiles.filter { f -> !f.canExecute() }.map { it.name }
        RuntimeDiagnostics.append(
            ctx, "prefix", absent.isEmpty() && missing.isEmpty(),
            if (absent.isEmpty() && missing.isEmpty())
                "\$PREFIX 件全部就位（" + packagedIds.size + " 件）"
            else "\$PREFIX 缺件：" + (absent + missing).joinToString(","),
            "落位根=" + lobos.kernel.layout.SystemDirs.opt(ctx).absolutePath +
                "  已落位=" + landedIds.size + "（" + landedIds.joinToString(",") + "）" +
                "  包里有=" + packagedIds.size + "（" + packagedIds.joinToString(",") + "）" +
                "  provision=" + provisioned.joinToString(",")
        )
        val prev = cached
        return Snapshot(prev?.prefixReady ?: landedIds.toList(), prev?.prefixMissing ?: emptyList())
    }
}

 
 
 
 
p
r
i
v
a
t
e
 
f
u
n
 
a
s
s
e
m
b
l
e
(
c
t
x
:
 
C
o
n
t
e
x
t
)
:
 
S
n
a
p
s
h
o
t
 
{


 
 
 
 
 
 
 
 
v
a
l
 
p
r
o
v
i
s
i
o
n
e
d
 
=
 
P
r
e
f
i
x
P
r
o
v
i
s
i
o
n
e
r
.
p
r
o
v
i
s
i
o
n
(
c
t
x
)


 
 
 
 
 
 
 
 
v
a
l
 
f
o
u
n
d
 
=
 
P
i
e
c
e
S
c
a
n
.
s
c
a
n
(
c
t
x
)
.
f
i
l
t
e
r
 
{
 
i
t
.
r
o
l
e
 
!
=
 
P
i
e
c
e
S
c
a
n
.
H
E
A
D
E
R
S
 
}


 
 
 
 
 
 
 
 
v
a
l
 
l
a
n
d
e
d
I
d
s
 
=
 
f
o
u
n
d
.
m
a
p
 
{
 
f
 
-
>
 
f
.
i
d
 
}
.
t
o
S
e
t
(
)


 
 
 
 
 
 
 
 
v
a
l
 
p
a
c
k
a
g
e
d
I
d
s
 
=
 
P
i
e
c
e
S
c
a
n
.
p
a
c
k
a
g
e
d
I
d
s
(
c
t
x
)


 
 
 
 
 
 
 
 
v
a
l
 
a
b
s
e
n
t
 
=
 
p
a
c
k
a
g
e
d
I
d
s
 
-
 
l
a
n
d
e
d
I
d
s


 
 
 
 
 
 
 
 
v
a
l
 
e
x
e
F
i
l
e
s
 
=
 
f
o
u
n
d


 
 
 
 
 
 
 
 
 
 
 
 
.
f
i
l
t
e
r
 
{
 
F
i
l
e
(
i
t
.
d
i
r
,
 
"
b
i
n
"
)
.
i
s
D
i
r
e
c
t
o
r
y
 
}


 
 
 
 
 
 
 
 
 
 
 
 
.
f
l
a
t
M
a
p
 
{
 
F
i
l
e
(
i
t
.
d
i
r
,
 
"
b
i
n
"
)
.
l
i
s
t
F
i
l
e
s
(
)
?
.
f
i
l
t
e
r
 
{
 
f
 
-
>
 
f
.
i
s
F
i
l
e
 
}
 
?
:
 
e
m
p
t
y
L
i
s
t
(
)
 
}


 
 
 
 
 
 
 
 
 
 
 
 
.
t
o
S
e
t
(
)


 
 
 
 
 
 
 
 
v
a
l
 
n
o
t
E
x
e
c
 
=
 
e
x
e
F
i
l
e
s
.
f
i
l
t
e
r
 
{
 
f
 
-
>
 
!
f
.
c
a
n
E
x
e
c
u
t
e
(
)
 
}
.
m
a
p
 
{
 
i
t
.
n
a
m
e
 
}


 
 
 
 
 
 
 
 
R
u
n
t
i
m
e
D
i
a
g
n
o
s
t
i
c
s
.
a
p
p
e
n
d
(


 
 
 
 
 
 
 
 
 
 
 
 
c
t
x
,
 
"
p
r
e
f
i
x
"
,
 
a
b
s
e
n
t
.
i
s
E
m
p
t
y
(
)
 
&
&
 
n
o
t
E
x
e
c
.
i
s
E
m
p
t
y
(
)
,


 
 
 
 
 
 
 
 
 
 
 
 
i
f
 
(
a
b
s
e
n
t
.
i
s
E
m
p
t
y
(
)
 
&
&
 
n
o
t
E
x
e
c
.
i
s
E
m
p
t
y
(
)
)


 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
"
$
P
R
E
F
I
X
 
件
全
部
就
位
（
"
 
+
 
p
a
c
k
a
g
e
d
I
d
s
.
s
i
z
e
 
+
 
"
 
件
）
"


 
 
 
 
 
 
 
 
 
 
 
 
e
l
s
e
 
"
$
P
R
E
F
I
X
 
缺
件
：
"
 
+
 
(
a
b
s
e
n
t
 
+
 
n
o
t
E
x
e
c
)
.
j
o
i
n
T
o
S
t
r
i
n
g
(
"
,
"
)
,


 
 
 
 
 
 
 
 
 
 
 
 
"
落
位
根
=
"
 
+
 
l
o
b
o
s
.
k
e
r
n
e
l
.
l
a
y
o
u
t
.
S
y
s
t
e
m
D
i
r
s
.
o
p
t
(
c
t
x
)
.
a
b
s
o
l
u
t
e
P
a
t
h
 
+


 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
"
 
 
已
落
位
=
"
 
+
 
l
a
n
d
e
d
I
d
s
.
s
i
z
e
 
+
 
"
（
"
 
+
 
l
a
n
d
e
d
I
d
s
.
j
o
i
n
T
o
S
t
r
i
n
g
(
"
,
"
)
 
+
 
"
）
"
 
+


 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
"
 
 
包
里
有
=
"
 
+
 
p
a
c
k
a
g
e
d
I
d
s
.
s
i
z
e
 
+
 
"
（
"
 
+
 
p
a
c
k
a
g
e
d
I
d
s
.
j
o
i
n
T
o
S
t
r
i
n
g
(
"
,
"
)
 
+
 
"
）
"
 
+


 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
"
 
 
p
r
o
v
i
s
i
o
n
=
"
 
+
 
p
r
o
v
i
s
i
o
n
e
d
.
j
o
i
n
T
o
S
t
r
i
n
g
(
"
,
"
)


 
 
 
 
 
 
 
 
)


 
 
 
 
 
 
 
 
v
a
l
 
p
r
e
v
 
=
 
c
a
c
h
e
d


 
 
 
 
 
 
 
 
r
e
t
u
r
n
 
S
n
a
p
s
h
o
t
(
p
r
e
v
?
.
p
r
e
f
i
x
R
e
a
d
y
 
?
:
 
l
a
n
d
e
d
I
d
s
.
t
o
L
i
s
t
(
)
,
 
p
r
e
v
?
.
p
r
e
f
i
x
M
i
s
s
i
n
g
 
?
:
 
e
m
p
t
y
L
i
s
t
(
)
)


 
 
 
 
}