package lobos.services.supply

import android.content.Context
import java.io.File
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitEntry
import lobos.services.reg.Level
import lobos.services.reg.edited
import lobos.services.log.RuntimeDiagnostics

object PieceScan {
    const val EXEC = "exec"

    const val SHELL = "shell"

    const val MULTI_COMMAND = "multi-command"
    const val LIBRARY = "library"
    const val HEADERS = "headers"

    data class Found(
        val id: String,
        val version: String,
        val dir: File,
        val entry: String,
        val role: String,
        val sha256: String,
        val meta: JSONObject? = null,
        val files: List<UnitEntry.FileRec> = emptyList(),
    ) {

        val required: Boolean get() = meta?.optBoolean("essential", false) ?: false

        val libName: String get() = entry.substringAfterLast('/', "")
    }
    fun scan(ctx: Context): List<Found> {
        val lib = SystemDirs.opt(ctx)
        val kids = lib.listFiles() ?: return emptyList()
        val out = mutableListOf<Found>()
        for (pieceDir in kids) {
            if (!pieceDir.isDirectory) continue
            val id = pieceDir.name
            if (File(pieceDir, "include").isDirectory) {
                val hf = filesOf(pieceDir)
                out += Found(id, "from-layout", pieceDir, "include", HEADERS,
                    sha256Of(pieceDir, hf), metaOf(pieceDir), hf)
                continue
            }
            for (verDir in pieceDir.listFiles() ?: emptyArray()) {
                if (!verDir.isDirectory) continue
                val version = verDir.name
                // 入口有效性只问「清单写的那份在不在」（§20.2:625 的不兜底仍然成立）；
                // **不再**由运行期读那份字节的 ELF 头回答（第三行规则）。无效则**报缺**：continue（不登记）。
                val declared = metaOf(verDir)?.optString("entry", "").orEmpty()
                val entry = entryOf(verDir, declared) ?: continue
                val files = filesOf(verDir)
                out += Found(
                    id = id,
                    version = version,
                    dir = verDir,
                    entry = entry,
                    role = roleOf(verDir),
                    sha256 = sha256Of(verDir, files),
                    meta = metaOf(verDir),
                    files = files,
                )
            }
        }
        return out.sortedBy { it.id }
    }

    /** 扫盘时登记的入口。**主语是清单里那一格**，不是目录名，也不是那份字节的 ELF 头。
     *   `declared` 是构建期写下的 entry（gen-component-meta.js 从落位结果算出）；
     *    它今天在不在，由 [usableEntryOf] 回答。
     *   缺陷名（C-102）：这里经过两代 —— 第一代先看 `bin/` 下有没有文件、再看有没有 `.so`
     *    （那是「看目录名」，§10.1:128 判为无效）；第二代读那份字节的 ET_DYN/PT_INTERP
     *    （那是「运行期探测形态」，第三行规则判为无效）。现在两代都去掉，只剩「在不在」。 */
    private fun entryOf(verDir: File, declared: String): String? =
        usableEntryOf(verDir, declared)

    /** role **只从清单里读**（2026-10-20 用户第三行规则：运行期只读，不判断、不探测）。
     *  读的是 `component-meta.json` 里那个构建期写下的 role 字段；字段缺失即报缺，不回退到任何探测。
     *
     *   **缺陷名（C-102）运行期探测形态**：原来这里是
     *    「有 include/  头；全目录找一份 ET_EXEC / ET_DYN+PT_INTERP  程序；
     *      找不到一份 ET_DYN 无 PT_INTERP  库；再找不到  仍是库」——
     *    整个 role 由**运行期自己扫盘读 ELF 头算出来**。而形态是构建期就知道的事（用户第一行规则），
     *    落位目录也是构建期定的（第二行规则） 这一问不该在运行期做。
     *   它之所以一直没有暴露，是因为 [LIBRARY] 同时是那条链末端的兜底返回（见改前 roleOf 末行），
     *    于是「扫不出程序」与「就是库」在结果上无法区分 —— 判据读错了主语（C-102）。 */
    private fun roleOf(verDir: File): String {
        val declared = metaOf(verDir)?.optString(ROLE_KEY, "").orEmpty().trim()
        if (declared.isNotBlank()) return declared
        // 缺陷名（C-102）**role 那一格在两份清单里，而这一处读的那一份没有它** ——
        // 而落位侧**确实**有那一份带 role 的清单（=> 而 ManifestWriter 写它，
        // => 而那一份是 program-manifest.json，=> 而它的 role 键逐字是 `role`）。
        //
        // 真机读数（07，2026-10-25，build-apk 40134460082 / sha=9144b24）：
        //   索引里 busybox 那一行的 role 逐字是 `entry-unusable`，
        //   而落位侧那份 program-manifest.json 的 role 逐字是 `program`，=> 而两处逐字不同；
        //   而后果是 busybox 那一件**没进监督名单**（journal 逐字「监督名单=bash,jq,ripgrep」），
        //   => 而它没被启动，=> 而「exit 变成 0」那一格**不存在**（=> 而不是 0，=> 而按 C-126 是未过）。
        //
        // 而**为什么落位侧那份 component-meta.json 没有 role 键**：
        //   => 而产物的 component-meta.json 由 gen-component-meta.js 写（=> 而它那个 meta 对象逐字是
        //      schema / id / version / entry / entryProvenance / requires / essential / source / sourceSha256
        //      / buildSha / buildRun，=> 而**没有 role**），
        //   => 而落位侧那一份是**照抄**产物那一份的（=> 而 PreinstalledPieces 那一支只是 copy），
        //   => 而所以 role 那一格**只**在 program-manifest.json 那一份里。
        //
        // 而按 C-103 这一族它为什么有两边：
        //   一边是「**产物自带的** component-meta.json 的 role」（=> 而它今天不存在，
        //        => 而加它要改 gen-component-meta.js，=> 而那在 PKG_MECH 里，=> 而而八件要重固化）；
        //   另一边是「**落位侧写的** program-manifest.json 的 role」（=> 而它**存在**，
        //        => 而而落位侧那一份是运行期写的，=> 而而它不用重新固化）。
        //
        // ⇒ 而**两份都读**（=> 而这就是本稿的处置）：
        //   ⇒ 而**先**读**产物那一份**（=> 而 :93 那一支在前面，=> 而而那一份从 41f3129 那一版起也带 role 键了，
        //      => 而它由构建期从落位结果算出，=> 而它是**真源**），
        //   ⇒ 而**读不到**才落到**落位侧那一份**（=> 而它是兜底，=> 而而它与监督环那一支读的是同一份）。
        // ⇒ 而这样两份**一致**（=> 而按 C-104「只有一条生成路径」），
        //   => 而而索引重建那一支拿到的就是 `program`（=> 而不是 `entry-unusable`）。
        // ⇒ 而**旧产物**（=> 而那一件是在 41f3129 之前固化的，=> 而而它那一份没有 role 键）
        //   => 而这一处仍**答得出来**（=> 而落位侧那一份有，=> 而而它就是兜底那一份）。
        val manifest = manifestOf(verDir)
        val mRole = manifest?.optString(ROLE_KEY, "").orEmpty().trim()
        return mRole.ifBlank { ENTRY_UNUSABLE }
    }

    /** 落位侧那一份清单（program-manifest.json）—— 落位时由 [ManifestWriter] 写。
     *  而**它与产物自带的 component-meta.json 是两份不同的清单**（=> 而前者由运行期写、
     *  => 而后者是产物照抄过来的），=> 而 role 那一格**只在**前者里。 */
    private fun manifestOf(verDir: File): JSONObject? {
        val f = File(verDir, lobos.services.supply.ProgramDir.MANIFEST_NAME)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /** 清单里 role 字段的键名。与 [ManifestWriter] 写的那一个同源（C-104：只有一条生成路径）。 */
    const val ROLE_KEY = "role"

    /** 这一件在磁盘上的 role —— **只从落位目录里那两份清单读**，不扫盘、不读 ELF 头。
     *  函数名保留原样（读者有四处，见 runtime-deletion-list.md 第 2 节），但主语已从「盘上那份字节」
     *  换成「清单里构建期写下的那一格」。
     *
     *   **缺陷名（C-102）先读声明再判字节**：原来这一段先取 `component-meta.json` 的 entry、
     *    再走 [roleOf] 扫盘判形态 —— 主语是盘上那份字节，而不是构建期写下的那一格。
     *    §10.1:138「一个字段若需要同时回答两个问题，拆成两个字段或废掉其中一个」 现在只读那一格。
     *
     *   **而那两份清单**（=> 而 41f3129 那一版起两份都带 role 键了，=> 而而读侧按「先真源后兜底」）：
     *    一、**产物自带的** `component-meta.json`（=> 而构建期写，=> 而而它是真源）；
     *    二、**落位侧写的** `program-manifest.json`（=> 而运行期写，=> 而而它兜底，
     *       => 而而它对**旧产物**也答得出来）。 */
    fun roleFromDisk(ctx: Context, id: String): String {
        // 找不到落位目录时**不猜**：按 §10.1:142「这一格的答案能不能从任意一个落位字节的 ELF 头读出来？
        // 不能  它不是判据，是我们的命名」 没有字节就没有答案，报缺（§10.1:119 的三态里「不可执行」那一态）。
        val dir = landedPieceDir(ctx, id)
            ?: return ENTRY_UNUSABLE
        return roleOf(dir)
    }

    /** 入口字段无效时返回的 role —— §20.2:619「报缺，而不是兜底到另一个目录去猜一个」。
     *   而**判据只有一条**（§20.2:621 的机械形式）：入口指向的那份字节能被当程序调起吗？
     *    不能  它不是入口  该单元无入口  报缺。
     *   「有无人引用」不在这条判据里 —— 07 今天真跑读数（**旧版落位那一版**：5a265b1）：
     *    jq 那件入口指向 `lib/liblobosjq.so`（-rw-------）
     *    而它 **exit=0 能调起**；crypto/curl/openssl/zlib 四件 exit=139。
     *     而那一版之后 entry 改了按形态算（纯库件 entry 留空、混合件 entry 指程序那一份），
     *      所以「jq 的入口指向 lib 那份」这个读数**是那一版的形态**，不是现在的。
     *   而**不兜底**是这一条的全部要点（§20.2:625）：一旦允许「换个目录猜一个」，
     *    §10.1:128 刚判为无效的「看目录名」那条读法就从后门回来。 */
    const val ENTRY_UNUSABLE = "entry-unusable"

    /** entry 字段的有效性：**只问「清单写的那份在不在」，不读它的 ELF 头**。
     *  @return 有效时返回该 entry（相对 verDir），无效时返回 null —— 调用方据此报缺，不猜。
     *
     *   **缺陷名（C-102）运行期探测形态**：原来这一行是
     *    `return if (lobos.kernel.elf.ExecBits.isRunnableByForm(target)) declaredEntry else null`
     *    —— 判据是「那份字节是 ET_EXEC 或 ET_DYN+PT_INTERP」，由运行期读 ELF 头算出。
     *    按第三行规则，运行期只读：entry 是构建期从落位结果算出来的（gen-component-meta.js），
     *    运行期只验**落位结果与清单是否一致**（文件在不在），不去问那份字节是什么形态。
     *    后果是：**一个 entry 指向的字节形态变了，这一处不再能拦住** ——
     *    形态是否正确的保证移到构建期（生成时就算错 entry 即判红退出）。
     *   而「有效性由那份字节自己回答」（§20.2:618）这一句今天**已不成立**，
     *    本次改动把它换成「由清单与落位结果的一致性回答」；这是判据主语的更换（C-102），不是收紧。 */
    fun usableEntryOf(verDir: File, declaredEntry: String): String? {
        if (declaredEntry.isBlank()) return null
        val target = File(verDir, declaredEntry)
        if (!target.isFile) return null
        return declaredEntry
    }

    private fun metaOf(verDir: File): JSONObject? {
        val f = File(verDir, "component-meta.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /** 落位目录的**相对**形态：opt/<id>（相对 filesDir）。
     *   读侧 ProgramManager.stateDirOf:81 按 `File(ctx.filesDir, e.stateDir)` 读，
     *    而 File(parent, child) 在 child 以 "/" 开头时把它当相对片段接在 parent 之后 
     *    写侧必须给相对形态，否则拼出双前缀（真机读数：program-state.json 的 evidence 那一格）。 */
    private fun relativeStateDir(ctx: Context, dir: File): String {
        // 剥掉的是 **filesDir**，不是 opt/：
        //   读侧 ProgramManager.stateDirOf 的那一支是 File(ctx.filesDir, e.stateDir)
        //   => 那一格必须相对 filesDir，所以要剥 filesDir。
        // 缺陷名（C-102）**剥错了那一层**：
        //   原来这里剥的是 opt/（含 id 段一起剥）=> 对 files/opt/busybox/1.36.1
        //   它算出 "busybox/1.36.1"，而 relStateDir 写进注册表的是 "opt/busybox"
        //   => **两个函数对同一件产出的不是同一个字符串**
        //   => 而读侧按 filesDir 解析 "busybox/1.36.1" => dir = files/busybox/1.36.1
        //   => dest = files/busybox/1.36.1/1.36.1（版本段两遍）
        //   => 真机读数（07）：files/busybox/1.36.1/1.36.1/，而注册表说 opt/busybox
        //   => **注册表说它在哪与它真正在哪不是同一处**
        val filesRoot = ctx.filesDir.canonicalFile.path
        val optRoot = SystemDirs.opt(ctx).canonicalFile.path
        val d = runCatching { dir.canonicalFile.path }.getOrDefault(dir.absolutePath)
        if (d.startsWith(optRoot + File.separator)) {
            // opt/<id>/<ver> => opt/<id>（只到 id，版本由目录名承担，不进这一格）
            //   而这一格必须与 ProgramManager.relStateDir（ProgramManager.kt:114）
            //   产出的形状一致：REL_OPT + "/" + id —— 否则两个写侧对同一件产出不同字符串。
            val id = d.substring(optRoot.length + 1).substringBefore(File.separatorChar)
            if (id.isNotBlank()) return SystemDirs.relPieceDir(id)
        }
        if (d.startsWith(filesRoot + File.separator)) {
            return d.substring(filesRoot.length + 1).replace(File.separatorChar, '/')
        }
        // 兜底：给不出相对形态就返回空，而**不伪造一个值**（C-05）。
        //   空值在读侧有现成语义（stateDir 空 = 这一件没有那个视图，
        //   见 ProgramIndex.levelOf:71 与 ShellEntry:12）。
        //   而这一支今天走不到：扫描的根（SystemDirs.opt）与 landedPieceDir
        //   （SystemDirs.opt().resolve(seg)）都在 opt/ 之下，落到上面两支。
        return ""
    }

    private fun filesOf(verDir: File): List<UnitEntry.FileRec> {
        val base = verDir.absolutePath
        val out = mutableListOf<UnitEntry.FileRec>()
        verDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name == "component-meta.json") return@forEach
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isEmpty()) return@forEach
            out += UnitEntry.FileRec(rel, SupplySha.sha256(f))
        }
        return out.sortedBy { it.path }
    }

    private fun sha256Of(verDir: File, files: List<UnitEntry.FileRec>): String {
        if (files.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (fr in files) {
            md.update(fr.path.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(fr.sha256.toByteArray(Charsets.UTF_8))
            md.update(10)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun rebuild(ctx: Context): Int {
        val found = scan(ctx)
        if (found.isEmpty()) return 0
        val cur = ProgramIndex.all(ctx).associateBy { it.id }
        var changed = 0
        var removed = 0
        for (f in found) {
            val prev = cur[f.id]
            val entry = (prev ?: ProgramIndex.empty(f.id, Level.PIECE)).edited(
                version = f.version,
                //  **缺陷名（C-102）stateDir 存绝对路径而读取侧按相对片段拼** ——
                //   原来这里写 f.dir.absolutePath（绝对），而 ProgramManager.stateDirOf:81
                //   读的是 File(ctx.filesDir, e.stateDir)：File(parent, child) 在 child 以 "/" 开头时
                //   把它当**相对片段**接在 parent 之后  落位目录被拼成
                //   /data/user/0/lobos.os/files/data/user/0/lobos.os/files/opt/<id>/<ver>
                //   —— 真机读数（07）：program-state.json 里 busybox 的 evidence 那一格就是这个双前缀。
                //   而装机那条（ProgramInstallPipeline.kt:334）写的是相对形态 relStateDir  两套口径并存。
                //   处置：写侧统一成相对（opt/<id>），与读侧与装机那侧同一口径。
                stateDir = relativeStateDir(ctx, f.dir),
                assetEntry = f.entry,
                role = f.role,
                sha256 = f.sha256,
                required = f.meta?.optBoolean("essential", false) ?: false,
                files = f.files,
            )
            if (prev != entry) {
                ProgramIndex.upsert(ctx, entry)
                changed++
            }
        }
        // 族一（这一件在磁盘上落在哪）· 事二：  登记里**不在 found 里**的那些，要删掉。
        //   缺陷名（C-102）**登记里的行结构性不会消失**：
        //     · ProgramIndex.upsert(:266-268) 只做「同 id 换掉 + 追加」，**从不删任何一行**
        //     · 而这一支遍历的是 found，  **cur 里不在 found 里的那些 id，它一个都不碰**
        //     · 而 ProgramIndex.remove(:271) 存在，  而它唯一的调用者是 PackageInstaller.kt:126，
        //       **而落位刷新这一支从不调它**
        //       ** 而「登记里那一行」今天结构性不会消失** 
        //     （  而 07 今天那句「下一次重装就会消失」不成立 ——   rebuild 仍然不删）
        //    现在：**按 found 的 id 集合取差，把不在的那些删掉**（  候选甲）
        //    而「删的判据」**不需要新发明** ——  它就是上面 :215 那一句的同一条：
        //     「扫不到就不动」（if (found.isEmpty()) return 0）
        //       扫得到的 = found； 登记里有而 found 里没有 = 该删的 
        //    而**只删 PIECE 那一档**：
        //      而这一支是「扫盘上的件」，  它不掌握程序的登记  
        //      **删一个不是件的条目，会越权**（  而 Level.PROGRAM 那几条是程序， 不是件）
        //       而那一格删哪一档**要 01/04 今天裁** ——  我今天只删「登记里标着是件的那些」
        val foundIds = found.map { it.id }.toSet()
        // 「这一件是不是一件」—— 而判据是**落位目录在不在 opt/ 底下**（而不用 levelOf，
        // 而 01 今天核过：levelOf 判的是 stateDir/assetEntry，**而那两格与「归谁管」不同问**）
        val optRootIds: Set<String> = runCatching {
            SystemDirs.opt(ctx).listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.toSet()
        }.getOrNull() ?: emptySet()
        if (optRootIds.isEmpty()) {
            // 而读不到 opt/ 底下有什么 => **那一格答不出「有没有该删的」**
            // => **不删**（「拿不到要答不知道」，而不能拿「没有」当答案）
            return changed
        }
        for (e in ProgramIndex.all(ctx)) {
            if (e.id in foundIds) continue
            // 这一格**不按 levelOf 判**—— 而 01 今天指出：levelOf 判的是 stateDir/assetEntry，            // **不是 role 字段** —— 而它答的是「有没有那个入口」，不是「这一件归谁管」：            // 真机读数（01 今天逐行核过，2026-10-21）：登记八行，role 是 4 library / 3 program，            // 而**按 levelOf 算**（assetEntry 空 => PIECE）是 **4 PIECE / 4 PROGRAM**            // **=> 而两把尺对同一行给出的答案不一样 => 用 levelOf 会误删**            // 例：openssl 那一行 role 是 library 而 assetEntry 是空，            // **=> levelOf 判它 PIECE => 那一行会被删** —— 而它是今天**在册**的那一件。            // => 而本支只管「扫件」（01 今天裁：而 scan 枚举的是 opt/ 底下那些，            // **而一个程序的落位目录根本不在 opt/**）=> **=> 判据是「它是不是一件」**            // **=> 而本支能看见的每一件，按定义都该由它管**            // => 而 found 之外的、又在 opt/ 底下的，才是「扫不到该删」            if (!optRootIds.contains(e.id)) continue
            if (ProgramIndex.remove(ctx, e.id)) {
                removed++
                RuntimeDiagnostics.append(
                    ctx, "scan", true, "登记里删掉（扫不到）" + e.id,
                    "这一件在落位目录里已经不存在，而登记里还有它那一行（  而 upsert 只换不删）",
                )
            }
        }
        return changed + removed
    }

    data class Verdict(val ok: Boolean, val checked: Int, val mismatched: List<String>)

    fun verify(ctx: Context, id: String): Verdict {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return Verdict(false, 0, emptyList())
        if (e.stateDir.isBlank()) return Verdict(false, 0, emptyList())
        if (e.files.isEmpty()) return Verdict(false, 0, emptyList())
        // 族一（落在哪）：这一处原来自己拼路径，而它把版本段丢了。
        //   缺陷名（C-102）**verify 拼出来的那条路径不存在**：
        //     原来这一行是 `val base = File(e.stateDir)`，而 e.stateDir 是**相对**形态
        //     （opt/bash），而 fr.path 是**件内**相对路径（bin/bash）
        //     => 拼出来既不相对 filesDir、也**缺版本段**
        //     => File("opt/bash", "bin/bash") = <cwd>/opt/bash/bin/bash（不存在）
        //     => 真机读数（07，2026-10-21）：八件**全部**答「坏」，而每一项都因为「不在」
        //     => 而它答「缺」不是判出了差异，是**每项都没找到**。
        //   判别形式：它拼出来的那条路径，真机上存不存在。
        //   => 现在：转调族一的真源（PieceScan.landedPieceDir，根是 SystemDirs.opt），
        //     而版本段由它自己选（取那个含清单的版本目录）。
        val base = landedPieceDir(ctx, id)
            ?: return Verdict(false, 0, listOf("找不到落位目录：" + e.stateDir + "/<版本>"))
        val bad = mutableListOf<String>()
        for (fr in e.files) {
            //  而登记里那一条（FileRec）**只有 path 与 sha256，没有 role** ——
            //    而 C-05：缺字段就报缺，不猜。所以这里**不按形态重算目录**（ 那要 role，
            //    而登记里没有那一格）， 而是直接用登记里那一条的 path：
            //    它要么已含目录（bin/bash）、 要么是裸名（而那时报缺，不猜它该落在哪）。
            val raw = fr.path
            if (!raw.contains('/')) {
                bad += "登记里的 path 没有目录（无法判定它该落在哪）：" + raw
                continue
            }
            val rel = raw
            val f = File(base, rel)
            when {
                !f.isFile -> bad += "缺：" + rel
                SupplySha.sha256(f) != fr.sha256 -> bad += "被换过：" + rel
            }
        }
        return Verdict(bad.isEmpty(), e.files.size, bad)
    }

    fun verifyAll(ctx: Context): Map<String, Verdict> {
        val out = linkedMapOf<String, Verdict>()
        for (e in ProgramIndex.all(ctx)) {
            out[e.id] = verify(ctx, e.id)
        }
        return out
    }
    fun landedId(path: String): String? {
        val p = path.replace(Regex("/+"), "/")
        val m = Regex("^(usr/)?opt/([^/]+)/").find(p) ?: return null
        return m.groupValues[2].ifBlank { null }
    }

    fun packagedIds(ctx: Context): Set<String> {
        val dir = "pieces"
        val names = runCatching { ctx.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        return names.mapNotNull { n ->
            val b = n.substringBeforeLast('.')
            b.takeIf { it.isNotBlank() && it != n }
        }.toSet()
    }

    fun landedPieceDir(ctx: Context, id: String): File? {
        val seg = id.replace("/", "")
        if (seg.isBlank()) return null
        val base = SystemDirs.opt(ctx).resolve(seg)
        if (!base.isDirectory) return null
        val vers = base.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: return null
        for (v in vers) {
            if (File(v, "component-meta.json").isFile) return v
        }
        return null
    }

    /** 这一件在盘上**是不是程序**（`UnitEntry.levelOf` 读的就是它）—— **只读清单里那一格**。
     *
     *   **缺陷名（C-102）运行期探测形态**：原来这一行是 `runnableEntryOf(dir) != null`，
     *    即「全目录扫两层，找一份 ET_EXEC / ET_DYN+PT_INTERP 的字节」。
     *    同一问题在 [roleOf] 与这里被读了两遍（两处扫盘、两把尺），而两条路对「扫不出程序」的件给出
     *    不同答案：[roleOf] 兜底返回 [LIBRARY]，这里兜底返回 false 而 [UnitEntry.levelOf] 据此判 Level.PIECE。
     *    现在两处读的是同一格（清单 role），这条不一致随之消失。
     *   找不到落位目录或清单里没有 role 时答 false（没有那一格就没有答案），不猜。 */
    fun runnableOnDisk(ctx: Context, id: String): Boolean {
        val dir = landedPieceDir(ctx, id) ?: return false
        val declared = metaOf(dir)?.optString(ROLE_KEY, "").orEmpty().trim()
        return declared.isNotBlank() && declared != LIBRARY && declared != HEADERS
    }

    fun isLandedPiece(ctx: Context, id: String): Boolean =
        landedPieceDir(ctx, id) != null

    fun pieceDir(ctx: Context, id: String): File? = landedPieceDir(ctx, id)

    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? =
        landedPieceDir(ctx, id)?.let { metaOf(it) }
}

internal object SupplySha {
    fun sha256(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
