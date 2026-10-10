package lobos.kernel

import android.content.Context

/**
 * 内核的状况上报钩子。
 *
 * 与 `PowerHostHooks` 同形：内核**产生事实**，但不去记 —— 诊断日志是服务层的事
 * （`services/log`）。所以内核只留一个可注入的回调，由上层决定记不记、记到哪。
 *
 * 存在的理由很实际：进程账本解析失败、PTY 宿主报错，这两件事若内核不报，
 * 出了就查不到；但若内核直接写诊断日志，它就依赖上了服务层，依赖方向反了。
 *
 * 用法：`KernelHooks.report(ctx, stage, ok, message, detail)`
 *
 * 与 PowerHostHooks 的区别：那边是「内核要叫宿主」，这边是「内核要说一句话」。
 * 两边都是同一个原则 —— 内核不认识宿主。
 */
object KernelHooks {

    @Volatile
    private var ensurer: ((Context) -> Unit)? = null

    @Volatile
    private var reporter: ((Context, String, Boolean?, String, String?) -> Unit)? = null

    /** 由服务层注册：被请求拉起宿主。 */
    fun setEnsurer(block: (Context) -> Unit) {
        ensurer = block
    }

    fun setReporter(block: (Context, String, Boolean?, String, String?) -> Unit) {
        reporter = block
    }

    fun clear() {
        ensurer = null
        reporter = null
    }

    /**
     * 上报一条状况。
     *
     * @param stage   阶段名，供上层分组
     * @param ok      true=可用 / false=出错 / null=仅记录
     */
    /**
     * 请求把宿主拉起。
     *
     * 无障碍服务连上后要反向拉起宿主（否则宿主没起、自动化无从谈起）。
     * 「怎么拉起」是服务层的事，内核只发出这个请求。
     */
    fun ensureHost(ctx: Context) {
        val r = ensurer ?: return
        runCatching { r(ctx.applicationContext) }
    }

    fun report(
        ctx: Context,
        stage: String,
        ok: Boolean?,
        message: String,
        detail: String? = null,
    ) {
        val r = reporter ?: return
        runCatching { r(ctx.applicationContext, stage, ok, message, detail) }
    }
}
