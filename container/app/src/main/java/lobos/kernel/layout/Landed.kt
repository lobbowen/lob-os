package lobos.kernel.layout

import android.content.Context
import java.io.File

/**
 * 按 id 定位一个落位的可执行入口。
 *
 * 存在的理由：内核里的执行通路（PTY、命令执行）需要回答
 * 「那个可执行文件在哪」，但**它不该知道系统里有哪些 id** ——
 * 「ptysession」「bash」是件的名字，是服务层的事。
 *
 * 所以内核只提供形状：给定一个落位根与入口名，返回那个位置。
 * 「哪个 id 对应哪个角色」由实现方（服务层）决定。
 *
 * 这是 Linux 的做法同构：`execve("/usr/bin/ls")` 里内核不认识 `ls`，
 * 它只知道 PATH 与 ELF 格式；「ls 是什么」是发行版与用户的事。
 *
 * @param root      该件的落位根（通常是 `usr/lib/<id>/<版本>/`）
 * @param entryRel  相对 root 的入口路径（形如 `bin/librivospty.so`）
 * @return 可执行文件；入口不存在时返回 null
 */
object Landed {

    /**
     * 给定落位根与相对入口名，定位那个文件。
     *
     * 只做形状解析，不判断「这件装了没」—— 那是账，服务层的事。
     */
    fun entryAt(root: File, entryRel: String): File? {
        if (root.path.isBlank() || entryRel.isBlank()) return null
        val f = File(root, entryRel)
        return f.takeIf { it.isFile }
    }

    /**
     * 按「件 id + 在册表里登记的入口」定位 —— 需要调用方提供入口名。
     *
     * 服务层用它：在册表查到 entry 后，把 entry 传进来。
     * 这样内核不认识 id，也不认识在册表。
     */
    fun entryOf(ctx: Context, stateDir: String, entryRel: String): File? =
        entryAt(File(stateDir), entryRel)
}
