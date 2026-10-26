package io.prism.phonecontrol.health

/**
 * 无障碍「合并写回」的**纯逻辑**（ADR-046 修订 2026-10-26；PRD US-3116 AC-2/AC-3）。
 *
 * **背景**：HyperOS/MIUI 安全中心会清除 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 * 中本应用的条目（真机实测：`null` + `accessibility_enabled=0`，而 `installedServiceCount=33`）。
 * 社区工具（sgsongx/androidAccServerManage）验证过"用 shell 写回 + 校验 + 重试"可行。
 *
 * **为什么必须是合并而非覆盖**：该设置是一个由 `:` 分隔的**共享列表**，用户可能同时启用了
 * 其它无障碍服务（读屏/自动化）。直接 `settings put ... <仅本服务>` 会把它们全部顶掉——
 * 这是不可接受的越界副作用。所以只**追加**，并保留原有顺序与条目。
 *
 * 本对象只做字符串处理（纯函数、可 JVM 单测）；真正写 settings 由 `AccessibilityRestorer` 执行。
 */
object AccessibilityMerge {

    /** 组件名分隔符（系统用 `:` 连接多个服务）。 */
    private const val SEP = ":"

    /**
     * 把 [component] 合并进现有列表。
     *
     * 规则（缺一不可）：
     * 1. 已有条目**原样保留**（含顺序）——不对用户既有服务做任何重排/去重以外的改动；
     * 2. [component] 已存在（**忽略大小写**，系统写回时可能大小写不同）则不重复追加；
     * 3. 忽略空条目与纯空白；
     * 4. 结果以 `:` 连接；全部为空时返回 [component]。
     *
     * @param existing `settings get secure enabled_accessibility_services` 的原始值
     *   （ROM 可能返回字面量 `"null"`）
     * @param component 本服务组件名（`<pkg>/<serviceClass>`）
     */
    fun append(existing: String?, component: String): String {
        val normalizedComponent = component.trim()
        if (normalizedComponent.isEmpty()) return existing?.trim().orEmpty().takeUnless { it == "null" }.orEmpty()
        val entries = parse(existing)
        val already = entries.any { it.equals(normalizedComponent, ignoreCase = true) }
        return if (already) {
            entries.joinToString(SEP)
        } else {
            (entries + normalizedComponent).joinToString(SEP)
        }
    }

    /**
     * 解析现有列表（处理 `null` 字面量、空串、多余分隔符、空白）。
     *
     * @return 去空后的条目列表（保留原顺序与重复项之外的原貌）
     */
    fun parse(existing: String?): List<String> {
        val raw = existing?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return emptyList()
        return raw.split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 从现有列表里**移除**指定组件（guardrail G-06 的回滚用）。
     *
     * 与 [append] 对称：只动目标条目，其余条目原样保留——这样"撤销我们这一笔"不会抹掉
     * 期间由系统/第三方新增的服务（整体写回写前快照会抹掉）。
     */
    fun remove(existing: String?, component: String): String {
        val target = component.trim()
        if (target.isEmpty()) return parse(existing).joinToString(SEP)
        return parse(existing)
            .filterNot { it.equals(target, ignoreCase = true) }
            .joinToString(SEP)
    }

    /**
     * 写回后是否需要重试 / 是否成功（纯判定，配合读回值使用）。
     *
     * @param written 期望写入的完整串
     * @param readBack 写后重读到的值
     */
    fun isApplied(written: String, readBack: String?): Boolean {
        val want = parse(written).map { it.lowercase() }.toSet()
        if (want.isEmpty()) return false
        val got = parse(readBack).map { it.lowercase() }.toSet()
        // 只要本服务在内的全部期望条目都在即视为成功（系统可能额外插入别的条目，不算失败）
        return got.containsAll(want)
    }

    /**
     * 写回是否**破坏了用户既有服务**（回归保护：写前有、写后没了 → 视为失败）。
     *
     * @param before 写前读到的值
     * @param after 写后读到的值
     */
    fun lostExisting(before: String?, after: String?): List<String> {
        val beforeSet = parse(before).map { it.lowercase() }.toSet()
        val afterSet = parse(after).map { it.lowercase() }.toSet()
        return parse(before).filter { it.lowercase() !in afterSet }
    }
}
