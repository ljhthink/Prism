package io.prism.phonecontrol.bg

/**
 * Shizuku 用户服务**单实例**相关的纯逻辑（ADR-046 修订 2026-10-26）。
 *
 * **背景**（真机取证）：设备上同时存在两个 `io.prism:shizuku` 进程（PID 889 / 20643），
 * `dumpsys display` 对应两个 `prism-hidden-vd` 残留。原因见
 * [Shizuku-API#2](https://github.com/RikkaApps/Shizuku-API/issues/2)：
 * - `UserServiceArgs.daemon` **默认 true** → 用户服务在 App 进程死后继续存活；
 * - `bindUserService` 只在 **version + component 完全一致**时复用，否则**新起一个**，
 *   旧进程**不会**被 Shizuku 杀掉。
 *
 * 因此必须由调用方自己保证"最多一个"。本对象只做**纯解析/纯决策**（`ps` 输出 → 待清理 PID），
 * 便于 JVM 单测；真正的 `kill` 由 `ShizukuShell` 以白名单命令执行。
 */
internal object ShizukuInstancePlan {

    /** 用户服务进程名后缀（与 `UserServiceArgs.processNameSuffix` 同源）。 */
    const val PROCESS_SUFFIX = "shizuku"

    /**
     * 本应用的用户服务进程名（`<package>:<suffix>`）。
     *
     * @param appId 应用包名（`io.prism`；不依赖 BuildConfig）
     */
    fun processName(appId: String): String = "$appId:$PROCESS_SUFFIX"

    /**
     * 从 `ps -A -o PID,NAME` 输出中挑出**需要清理**的 PID。
     *
     * **安全约束（缺一不可）**：
     * 1. 仅匹配进程名**完全等于** `<appId>:shizuku`（不做前缀/包含匹配——否则会误伤
     *    `io.prism:shizuku_extra` 之类，或用户其它 App 的同名后缀进程）；
     * 2. 排除 [keepPid]（当前正在使用的服务），避免"清理孤儿把自己杀了"；
     * 3. 排除非正数 PID 与重复项；
     * 4. 任何解析失败的行直接忽略（`ps` 输出格式因 ROM 而异，宁少杀不错杀）。
     *
     * @param psOutput `ps -A -o PID,NAME` 的原始输出
     * @param appId 应用包名
     * @param keepPid 需要保留的 PID（当前绑定；`<=0` 表示没有需要保留的）
     * @return 待 `kill` 的 PID 列表（可能为空）
     */
    fun orphanPids(psOutput: String?, appId: String, keepPid: Int): List<Int> {
        if (psOutput.isNullOrBlank()) return emptyList()
        val target = processName(appId)
        val out = LinkedHashSet<Int>()
        psOutput.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("PID")) return@forEach
            val cols = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (cols.size < 2) return@forEach
            val pid = cols[0].toIntOrNull() ?: return@forEach
            val name = cols[1]
            if (pid <= 0 || pid == keepPid) return@forEach
            if (name == target) out += pid
        }
        return out.toList()
    }

    /**
     * 日志用：把 PID 列表渲染成紧凑串（空列表返回 `-`）。
     */
    fun describe(pids: List<Int>): String = if (pids.isEmpty()) "-" else pids.joinToString(",")

    private val WHITESPACE = Regex("\\s+")
}
