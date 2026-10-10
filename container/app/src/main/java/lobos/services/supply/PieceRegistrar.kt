package lobos.services.supply

import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import android.content.Context

/**
 * 把「铺好的件」登记进在册表。
 *
 * 这一段原本在 kernel/layout/PrefixProvisioner 里，名为 registerProvisioned。
 * 它不是形状 —— 形状是「东西该放哪」，而这一段是「放完了告诉谁」。
 * 内核不认识 unit 在册表，那是服务层的事，所以拆出来。
 *
 * 铺位（内核）+ 记账（本文件）是一次动作的两半：铺是机制，记是账。
 */
object PieceRegistrar {

    /**
     * 扫落位，把与在册表不一致的条目写回去。
     * 已一致的跳过 —— 每次宿主 tick 都调，不该反复写盘。
     */
    fun register(ctx: Context) {
        for (f in PieceScan.scan(ctx)) {
            val prev = lobos.services.reg.ProgramIndex.get(ctx, f.id)
            if (prev != null && prev.version == f.version && prev.stateDir == f.dir.absolutePath) {
                continue
            }
            lobos.services.reg.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.services.reg.ProgramIndex.empty(f.id, lobos.services.reg.Level.PIECE)).copy(
                    version = f.version,
                    stateDir = f.dir.absolutePath,
                    assetEntry = f.entry,
                    role = f.role,
                    sha256 = f.sha256,
                ),
            )
        }
    }

    /**
     * 这个落位是不是被 OTA 更新管着的（而不是 APK 原件）。
     *
     * 判据：是我们建的软链，且指向 usr/lib 之下。
     */
    fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        // 不是软链就不是我们建的 —— 先判形，再看它指向哪
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) false
        else dst.toPath().toRealPath().startsWith(PrefixProvisioner.libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

}
