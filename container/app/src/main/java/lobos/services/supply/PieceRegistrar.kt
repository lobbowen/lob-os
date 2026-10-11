package lobos.services.supply

import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import android.content.Context

object PieceRegistrar {

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

    fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) false
        else dst.toPath().toRealPath().startsWith(PrefixProvisioner.libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

}
