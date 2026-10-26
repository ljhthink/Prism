package io.prism.phonecontrol.bg

/**
 * 解析 `dumpsys window windows`，找出**主屏（display 0）**上属于指定用户的窗口包名
 * （guardrail **G-02** 的判据）。
 *
 * **为什么不用无障碍 `getWindowsOnAllDisplays`**：它只能给出包名，**无法区分同名包的不同
 * 用户实例**。QQ 小号在 XSpace（user 999），用户自己也在用同一个包（user 0）——
 * 若只按包名判定，"清理泄漏窗口"会杀掉用户自己在用的 App。
 *
 * `dumpsys window windows` 的 `Window{<hash> u<userId> <pkg>/<activity>}` 头行**带 userId**，
 * 且每个窗口块内的 `mDisplayId=` 给出所属显示；两者组合即可精确判定。
 * shell 持有 `android.permission.DUMP`，Shizuku 用户服务可直接读取。
 *
 * 纯字符串解析（可 JVM 单测）：解析失败一律返回"空集合"，调用方据"空=不动作"保持 fail-safe。
 */
internal object MainDisplayWindows {

    /** 一个窗口块的摘要。 */
    data class Win(val userId: Int, val packageName: String, val displayId: Int)

    /** 头行：`Window #15 Window{d8042b u999 com.tencent.mobileqq/com.tencent...SplashActivity}:` */
    private val HEADER = Regex("""^\s*Window\s+#\d+\s+Window\{[0-9a-f]+\s+u(-?\d+)\s+([^\s/]+)/""")

    /** 所属显示：`mDisplayId=0 taskId=...` */
    private val DISPLAY = Regex("""mDisplayId=(-?\d+)""")

    /**
     * **显示分区头**：`  Display: mDisplayId=0 (organized)` / `  Display #0 (activities...)`。
     *
     * guardrail **R2-05**：分区头本身也含 `mDisplayId=`，若无条件按"块内首个 mDisplayId"
     * 取值，紧跟在头行之后的**另一个窗口块**可能把分区头的 displayId 吞掉，
     * 造成"窗口被算到错误显示上"。故分区头单独识别并**立即作废当前块**。
     */
    private val DISPLAY_SECTION = Regex("""^\s*Display[: #]\s*(?:mDisplayId=)?(-?\d+)""")

    /**
     * 解析出 display 0 上的窗口列表。
     *
     * @param dump `dumpsys window windows` 原始输出（可为 null）
     */
    fun parse(dump: String?): List<Win> {
        if (dump.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Win>()
        var curUser = Int.MIN_VALUE
        var curPkg: String? = null
        var curDisplay: Int? = null
        fun flush() {
            val pkg = curPkg
            val disp = curDisplay
            if (pkg != null && disp != null && curUser != Int.MIN_VALUE) {
                out += Win(curUser, pkg, disp)
            }
            curUser = Int.MIN_VALUE
            curPkg = null
            curDisplay = null
        }
        dump.lineSequence().forEach { line ->
            val head = HEADER.find(line)
            if (head != null) {
                flush()
                curUser = head.groupValues[1].toIntOrNull() ?: Int.MIN_VALUE
                curPkg = head.groupValues[2]
                return@forEach
            }
            // 分区头：作废尚未取到 displayId 的残留块（R2-05）
            if (curPkg != null && curDisplay == null && DISPLAY_SECTION.containsMatchIn(line)) {
                curUser = Int.MIN_VALUE
                curPkg = null
                return@forEach
            }
            if (curPkg != null && curDisplay == null) {
                val d = DISPLAY.find(line)
                if (d != null) curDisplay = d.groupValues[1].toIntOrNull()
            }
        }
        flush()
        return out
    }

    /**
     * 主屏（display 0）上属于 [userId] 的可见窗口包名集合。
     *
     * @param dump `dumpsys window windows` 输出
     * @param userId 目标用户（0=用户本人；999=XSpace）
     * @return 包名集合；空集合表示"未发现"（调用方不得据此清理其它对象）
     */
    fun packagesOnMainDisplayForUser(dump: String?, userId: Int): Set<String> =
        parse(dump)
            .asSequence()
            .filter { it.displayId == DEFAULT_DISPLAY && it.userId == userId }
            .map { it.packageName }
            .toSet()

    /** `Display.DEFAULT_DISPLAY`（避免依赖 Android 类，便于单测）。 */
    const val DEFAULT_DISPLAY = 0
}
