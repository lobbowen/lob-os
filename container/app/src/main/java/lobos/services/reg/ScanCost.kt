package lobos.services.reg

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 那**扫描成本**那一族的开关 —— 「跳过 rebuild」那一档。
 *
 * 缺陷名（C-102）**那扫描逐字对那每一个文件都算 SHA-256、=> 而而那 App 起来逐字活约 20 秒就被杀**
 *   => 而那 `PieceScan.filesOf` 逐字 walkTopDown 逐字 sha256 `files/opt` 底下**每一个**文件
 *     （=> 而那设备上逐字 **18880** 个：python **8096**、sysroot **3385**、npm **1937**、
 *       llvm **901**、git 224、openssl 149）
 *     => 而而那那 ROM 的 service 超时逐字是 **20004ms** 那一档
 *     => 而而那凡「App 起来后要跑满服务池」的那一格、逐字在这台设备上**都取不到**
 *       （=> 而那**不只是** M7-12 那一格）。
 *   => 而那**处置**：=> 而那**扫描**逐字**要**（=> 而那它逐字答「哪几件在位」），
 *     => 而而那**逐文件算 sha256** 那一件逐字**要能跳过**。
 *
 * ★ 这一格逐字**不是**「跳过一切扫描」——而那它逐字**有一个明确的语义**：
 *   · **仍然**扫那**目录**、**仍然**答「哪几件在位」、**仍然**答「哪一个入口在不在」、
 *     **仍然**答那**缺件**那一族（=> 而那 `absent` 与那 `missing` 逐字要那扫描结果）。
 *   · **不做**的是「那**每一个**文件**读一遍并算一遍 sha256」那一件。
 *   · => 而而那**内容指纹**逐字**报缺**（=> 而按 C-05）、=> 而而那**不**拿一个空的冒充它。
 *
 * ★ 这一格逐字**在哪一层**：=> 而它是那**运行期**那一层（=> 而那它逐字管那**扫描成本**），
 *   => 而而那它逐字**不是**一个探针（=> 而它是**产品那一面**：=> 而那一族逐字要能控制那扫描的成本）。
 *
 * ★ 怎么开：=> 而那**设备侧**逐字一个文件（=> 而那 `files/etc/` 底下那一份），
 *   => 而那格式逐字是那 JSON：
 *   ```
 *   { "skipContentFingerprint": true }
 *   ```
 *   => 而那读不到时逐字用那**默认**（=> 而那默认逐字是**算**那内容指纹、=> 而而那**不**改变任何东西）。
 *   => 而那它逐字**只**管那**内容指纹**那一步、=> 而而那**别的**逐字**一概不**跳过。
 */
object ScanCost {

    private const val CONFIG_NAME = "scan-cost.json"

    /** 那开关的默认态：=> 而那**默认**逐字是**算**那内容指纹（=> 而而那**不**改变任何东西）。 */
    const val DEFAULT_WITH_CONTENT_FINGERPRINT = true

    /**
     * 那开关逐字现在是什么态。
     *   => 而那读**设备侧**那一份（=> 而那 `files/etc/scan-cost.json`）、
     *     => 而那读不到 / 读不成 / 那字段不是布尔 => 而那**默认**（=> 而按 C-05 不猜填）。
     */
    fun withContentFingerprint(ctx: Context): Boolean {
        val f = configFile(ctx)
        if (!f.isFile) return DEFAULT_WITH_CONTENT_FINGERPRINT
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.opt("skipContentFingerprint")
            //  => 而那字段逐字**不是**布尔时逐字**按缺**处理（=> 而按 C-05 报缺、=> 而不猜填）
            if (v is Boolean) !v else DEFAULT_WITH_CONTENT_FINGERPRINT
        }.getOrDefault(DEFAULT_WITH_CONTENT_FINGERPRINT)
    }

    /** 那配置逐字在哪（=> 而那它是**设备侧**那一族、=> 而它逐字**不在** APK 里）。 */
    fun configFile(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.etc(ctx), CONFIG_NAME)

    /**
     * 那开关逐字是什么态逐字**报出来**（=> 而那它逐字进那诊断那一族、=> 而那读设备的人逐字看得见）。
     *   => 而那**默认**态也逐字报（=> 而那它逐字是「那内容指纹逐字**算**」）。
     */
    fun describe(ctx: Context): String {
        val on = withContentFingerprint(ctx)
        val src = if (configFile(ctx).isFile) "设备侧 " + configFile(ctx).absolutePath else "默认（无设备侧配置）"
        return "内容指纹=" + (if (on) "算" else "跳过") +
            " 扫描=" + "仍然扫目录" +
            " 来源=" + src
    }
}
