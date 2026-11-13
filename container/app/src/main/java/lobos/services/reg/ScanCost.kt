package lobos.services.reg

import android.content.Context
import java.io.File
import org.json.JSONObject

/* *
 * 那**扫描成本**那一族的开关 —— 「跳过 rebuild」那一档。
 *
 * 缺陷名（C-102）**那扫描逐字对那每一个文件都算 SHA-256、 App 起来逐字活约 20 秒就被杀**
 * `PieceScan.filesOf` 逐字 walkTopDown 逐字 sha256 `files/opt` 底下**每一个**文件
 * （ 设备上逐字 **18880** 个：python **8096**、sysroot **3385**、npm **1937**、
 * llvm **901**、git 224、openssl 149）
 * ROM 的 service 超时逐字是 **20004ms** 那一档
 * 凡「App 起来后要跑满服务池」的那一格、逐字在这台设备上**都取不到**
 * （ **不只是** M7-12 那一格）。
 * **处置**： **扫描**逐字**要**（ 它逐字答「哪几件在位」），
 * **逐文件算 sha256** 那一件逐字**要能跳过**。
 *
 * 这一格逐字**不是**「跳过一切扫描」——而那它逐字**有一个明确的语义**：
 * · **仍然**扫那**目录**、**仍然**答「哪几件在位」、**仍然**答「哪一个入口在不在」、
 * **仍然**答那**缺件**那一族（ `absent` 与那 `missing` 逐字要那扫描结果）。
 * · **不做**的是「那**每一个**文件**读一遍并算一遍 sha256」那一件。
 * · **内容指纹**逐字**报缺**（ 按 C-05）、 **不**拿一个空的冒充它。
 *
 * 这一格逐字**在哪一层**： 它是那**运行期**那一层（ 它逐字管那**扫描成本**），
 * 它逐字**不是**一个探针（ 它是**产品那一面**： 一族逐字要能控制那扫描的成本）。
 *
 * 怎么开： **设备侧**逐字一个文件（ `files/etc/` 底下那一份），
 * 格式逐字是那 JSON：
 * ```
 * { "skipContentFingerprint": true, "skipTraversal": false }
 * ```
 * 读不到时逐字用那**默认**（ 默认逐字是**算**那内容指纹、 **不**改变任何东西）。
 * 它逐字**只**管那**内容指纹**那一步、 **别的**逐字**一概不**跳过。
 * **档二 · 遍历本身**逐字是**另一个**开关（ `skipTraversal` 那一格）
 * （ 它逐字答的是「那遍历本身要不要做」、 它逐字**不**答「那内容指纹算不算」
 * **两档逐字分开**、 **不并**）。
 */
object ScanCost {

    private const val CONFIG_NAME = "scan-cost.json"

    /* * 那开关的默认态： **默认**逐字是**算**那内容指纹（ **不**改变任何东西）。 */
    const val DEFAULT_WITH_CONTENT_FINGERPRINT = true

    /* *
     * 那开关逐字现在是什么态。
     * 读**设备侧**那一份（ `files/etc/scan-cost.json`）、
     * 读不到 / 读不成 / 那字段不是布尔 **默认**（ 按 C-05 不猜填）。
     */
    fun withContentFingerprint(ctx: Context): Boolean {
        val f = configFile(ctx)
        if (!f.isFile) return DEFAULT_WITH_CONTENT_FINGERPRINT
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.opt("skipContentFingerprint")
            // 字段逐字**不是**布尔时逐字**按缺**处理（ 按 C-05 报缺、 不猜填）
            if (v is Boolean) !v else DEFAULT_WITH_CONTENT_FINGERPRINT
        }.getOrDefault(DEFAULT_WITH_CONTENT_FINGERPRINT)
    }

    /* *
     * 那**档二 · 遍历本身**那一档 —— 「那一次扫描逐字要多久」逐字**答**在**这一档**上。
     *
     * 缺陷名（C-102）**那开关逐字只覆盖了那一族的一半、 它逐字没有越界声称覆盖那一族**
     * 旧版逐字**只有**那 `skipContentFingerprint` 那一档
     * （ 它逐字只管那**内容指纹**那一步、 **别的**逐字**一概不**跳过）
     * **后果**（07 本轮逐字核出）： `filesOf` 那一格逐字**仍然** walkTopDown
     * 逐字 stat 那**每一个**文件（ files/opt 逐字 **18880** 个）
     * **只**省了 hash、 **没省那遍历**
     * 17 分钟那一读逐字**与**那遍历一致（ 07 逐字**没有**栈、 按 C-113/C-116
     * 标明「**值是读出来的、归因是定位出来的**」）
     * **判别形式**（01 逐字核的）：
     * 「凡要答『那一次扫描要多贵』，判据必须落在『那遍历本身』上，
     * **不许只落在『那内容指纹算不算』那一步**；
     * 一个开关要么覆盖全部、要么不许声称覆盖那一族」
     *
     * 这一档逐字**独立**： 它逐字答的是「那遍历本身要不要做」
     * 它逐字**不**答「那内容指纹算不算」（ 是**档一**）
     * **两档逐字分开**、 **不并**（依 C-105）。
     *
     * **档二**关掉时逐字**只**答那**目录视图**（ 它逐字**不**是「跳过一切扫描」）：
     * 它逐字**仍然**答「哪几件在位」、 **入口**那一格逐字**留空**
     * （ 按 C-05 报缺、 不猜填）、 **缺件**那一族逐字仍然答。
     */
    fun traverseFiles(ctx: Context): Boolean {
        val f = configFile(ctx)
        if (!f.isFile) return true
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.opt("skipTraversal")
            if (v is Boolean) !v else true
        }.getOrDefault(true)
    }

    /* * 那配置逐字在哪（ 它是**设备侧**那一族、 它逐字**不在** APK 里）。 */
    fun configFile(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.etc(ctx), CONFIG_NAME)

    /* *
     * 那开关逐字是什么态逐字**报出来**（ 它逐字进那诊断那一族、 读设备的人逐字看得见）。
     * **默认**态也逐字报（ 它逐字是「那内容指纹逐字**算**」）。
     */
    fun describe(ctx: Context): String {
        val on = withContentFingerprint(ctx)
        val walk = traverseFiles(ctx)
        val src = if (configFile(ctx).isFile) "设备侧 " + configFile(ctx).absolutePath else "默认（无设备侧配置）"
        return "内容指纹=" + (if (on) "算" else "跳过") +
            " 遍历=" + (if (walk) "做" else "不做") +
            " 目录视图=仍然扫目录" +
            " 来源=" + src
    }
}
