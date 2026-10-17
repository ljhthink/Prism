package io.prism.pet.model

/**
 * 批次24（ADR-051 D3）：桌宠形象的类型 —— 决定渲染后端。
 *
 * - [LIVE2D]：`.petpack` 内含 Cubism 全套资产（`.model3.json` / `.moc3` / 纹理 / motions / expressions /
 *   physics），由 WebView + pixi-live2d-display 渲染，支持动作、表情、物理、hit area；
 * - [IMAGE]：单张立绘或等间隔序列帧，由同一渲染器以"位移/缩放/抖动 + 逐帧切换"的**视觉近似**实现同一动作契约。
 *
 * **为什么必须有 [IMAGE]**（ADR-051 D3）：需求要求"用户自行上传角色形象"，而绝大多数用户手上只有一张立绘；
 * 只支持 Live2D 会把这些人挡在门外。同时它是 Live2D 渲染失败时的**降级兜底**（PRD US-2413 AC-1）。
 */
enum class PetAppearanceKind {
    LIVE2D,
    IMAGE;

    companion object {
        /** 宽松解析：大小写不敏感；未知值返回 null（调用方兜底 [IMAGE]，最保底的渲染路径）。 */
        fun fromWire(raw: String?): PetAppearanceKind? = when (raw?.trim()?.lowercase()) {
            "live2d" -> LIVE2D
            "image", "img", "sprite" -> IMAGE
            else -> null
        }
    }
}

/** 动作种类：模型动作（含物理/形变）或表情。 */
enum class PetActionKind {
    MOTION,
    EXPRESSION;

    companion object {
        fun fromWire(raw: String?): PetActionKind? = when (raw?.trim()?.lowercase()) {
            "motion" -> MOTION
            "expression", "expr" -> EXPRESSION
            else -> null
        }
    }
}

/**
 * 一次"动作"的解析结果（纯数据，无 Android 依赖）。
 *
 * @property kind 动作种类
 * @property target [PetActionKind.MOTION] 时为模型内的 **motion 组名**（如 `动作#6`）；
 *   [PetActionKind.EXPRESSION] 时为 **expression 名**（如 `开心眼`）
 * @property index 该组内的动作下标。**约定：`< 0` 表示"组内随机"**（渲染层对该值调用
 *   `model.motion(group)` 而非 `model.motion(group, index)`，即 `startRandomMotion`）。
 *   取值为 {@link #RANDOM_MOTION_INDEX} 时最适合"待机动作"这类"每次都希望不一样"的别名。
 *   `>= 0` 时由渲染层钳制到组内合法范围（越界不崩溃，走 [fallbackExpression] 回退）。
 * @property displayName 面向用户/日志的可读名（可选；用于设置页与工具返回文案）
 * @property fallbackExpression 动作执行失败时的**表情回退**（可选）。
 *
 *   **为什么必须有这个字段**（移动端调研报告 §6 实测结论，本批次最关键的功能发现）：
 *   昔涟模型的 `model3.json` 里 **23 条 motion 中有 14 条只有 `Expression` 而无 `File`**
 *   （Live2DViewerEX 用它表达"这个动作其实是一次表情切换"），且 `Groups` 为空。
 *   官方 `CubismModelSettingJson` 解析器**完全不读** `Motions[].Expression` 这类扩展字段，
 *   因此标准运行时会把它当成"一个没有文件的 motion"——加载返回 undefined、播放无反应。
 *   上游 `interaction.ts` 的对策正是"**motion 失败 → 退化为 expression**"。
 *   若不做这层回退，用户点下去会有超过一半的部位"点了没反应"（静默失败）。
 */
data class PetActionRef(
    val kind: PetActionKind,
    val target: String,
    val index: Int = 0,
    val displayName: String? = null,
    val fallbackExpression: String? = null
) {
    /** 供日志/工具返回值使用的稳定标识（不含用户内容，可安全落日志）。 */
    val wireId: String
        get() = if (kind == PetActionKind.MOTION) "motion:$target#$index" else "expression:$target"

    companion object {
        /**
         * [index] 的"随机"哨兵值：渲染层据此在组内随机挑一个动作。
         *
         * 用负数而非 0 表达随机，是因为 0 是**合法的具体动作**（如 `动作#6` 的第 0 条是
         * "动作回正"）——用 0 兼作随机会让"每次都能复现同一个动作"与"每次随机"无法区分。
         */
        const val RANDOM_MOTION_INDEX = -1
    }
}

/**
 * 一个已安装的桌宠形象（纯数据）。
 *
 * **路径语义**（安全边界，ADR-051 风险表）：[model] / [images] 全部是**相对于该形象根目录**的
 * 相对路径，由 [io.prism.pet.asset.PetPackInstaller] 在安装期规范化并校验落在根目录内。
 * 渲染层拿到的是**已校验的相对路径**，永远不接触用户输入或绝对路径。
 *
 * @property id 形象唯一标识（同时是磁盘目录名，已规范化）
 * @property name 显示名
 * @property kind 渲染后端类型
 * @property model [PetAppearanceKind.LIVE2D] 时 `.model3.json` 的相对路径；[PetAppearanceKind.IMAGE] 时为 null
 * @property images [PetAppearanceKind.IMAGE] 时的帧图相对路径（单元素 = 静态图；多元素 = 序列帧）
 * @property frameDurationMs 序列帧每帧时长（毫秒）；<=0 由渲染层钳制为默认值
 * @property scale 形象在窗口内的基础缩放（不同模型 canvas 尺寸差异大，需 per-形象可调）
 * @property actions 别名 → 动作映射（键为小写别名，见 [PetActionCatalog.normalizeAlias]）
 * @property hitActions 命中区名 → 别名映射（点到模型某个部位时播哪个别名）。
 *
 *   **必须由形象包显式提供**：Cubism 官方 `model3.json` 规范里的 `HitAreas[]` **只有 `Name`/`Id`**，
 *   不含"命中后播什么"；昔涟模型把该信息写在**非标准**的 `HitAreas[].Motion` 里，
 *   而标准运行时（pixi-live2d-display 与官方 `CubismModelSettingJson` 均如此）**完全不读**该字段。
 *   故映射只能由 `pet.json` 补齐；未列出的命中区走 [tapAlias] 兜底。
 * @property idleAliases 待机动作候选别名（空 → 渲染层不主动做待机动作）
 * @property tapAlias 点击未命中任何 hit area 时的默认别名（null → 渲染层随机挑一个待机动作）
 * @property defaultExpression 初始化时设置的表情名（null → 模型默认）
 * @property expressionResetSeconds 表情自动回正秒数（0 = 不自动回正）
 * @property attribution 署名/来源说明（合规要求：昔涟模型必须署名原作者，见 ADR-051 D4）
 */
data class PetAppearance(
    val id: String,
    val name: String,
    val kind: PetAppearanceKind,
    val model: String? = null,
    val images: List<String> = emptyList(),
    val frameDurationMs: Int = DEFAULT_FRAME_MS,
    val scale: Float = 1.0f,
    val actions: Map<String, PetActionRef> = emptyMap(),
    val hitActions: Map<String, String> = emptyMap(),
    val idleAliases: List<String> = emptyList(),
    val tapAlias: String? = null,
    val defaultExpression: String? = null,
    val expressionResetSeconds: Int = DEFAULT_EXPRESSION_RESET_SECONDS,
    val attribution: String? = null
) {
    companion object {
        /** 序列帧默认每帧时长（毫秒）。 */
        const val DEFAULT_FRAME_MS = 120

        /** 表情自动回正默认秒数（对齐上游 expression-reset 的观感）。 */
        const val DEFAULT_EXPRESSION_RESET_SECONDS = 8
    }
}

/**
 * 批次24（ADR-051 D8）：桌宠**动作目录** —— 别名解析的单一事实来源（纯函数，可 JVM 单测）。
 *
 * **背景**：上游 `Cyrene-Agent` 用 `src/shared/live2d-actions.ts` 作为"LLM 能说的动作别名"的唯一清单，
 * 主进程据此构造 `play_live2d_action` 工具描述、渲染进程据此把别名映射到 `motion()` / `expression()`。
 * 本类是该设计的移动端等价物，但**多了一层间接**：别名 → 形象自身的动作映射由形象包（`pet.json`）提供，
 * 因为移动端要支持**任意用户上传的形象**——内置别名集合是稳定的，具体模型的动作名千差万别。
 *
 * **契约**：
 * 1. 别名**大小写不敏感**、两侧空白裁剪（[normalizeAlias]）；
 * 2. 形象未映射的别名 → [resolve] 返回 null，**安全忽略**（不抛异常、不报错弹窗）；
 * 3. 内置别名集合 [BUILTIN_ALIASES] 是**约定**而非硬约束：形象可以只映射其中一部分，
 *    也可以映射额外自定义别名（工具与点击派发同样能用）。
 */
object PetActionCatalog {

    /**
     * 内置通用别名（跨形象稳定的"语义动作"词汇表）。
     *
     * 选取依据：① 与上游 `live2d-actions.ts` 的语义动作类目对齐（情绪 + 交互 + 复位）；
     * ② 覆盖 `pet__play_action` 工具枚举的可见值（让模型有稳定的可选词汇）；
     * ③ 每个别名在 [io.prism.pet.PetLocalToolExecutor] 的描述里都有中文释义。
     */
    val BUILTIN_ALIASES: List<String> = listOf(
        "idle",       // 待机/自然动作
        "tap",        // 被点到时的反应
        "happy",      // 开心
        "sad",        // 低落
        "angry",      // 生气
        "surprised",  // 惊讶
        "shy",        // 害羞
        "think",      // 思考
        "wink",       // 眨眼/俏皮
        "wave",       // 打招呼
        "reset"       // 复位（表情回正 + 停止当前动作）
    )

    /**
     * 归一化别名：裁剪空白 + 转小写。
     *
     * **为什么必须归一化**：别名同时来自三个不可信/不一致的入口——`pet.json`（用户资产）、
     * LLM 工具参数（模型可能给 `Happy` / `" happy "`）、点击派发（内部常量）。若不做归一化，
     * `Happy` 会静默解析失败，表现为"模型说了动作但角色没反应"这类难查的静默缺陷。
     */
    fun normalizeAlias(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * 把别名解析为该形象的 [PetActionRef]。
     *
     * @return 命中返回动作引用；不存在 / 未映射 / 目标空白 返回 null（调用方按"安全忽略"处理）
     */
    fun resolve(appearance: PetAppearance?, alias: String?): PetActionRef? {
        val key = normalizeAlias(alias) ?: return null
        val ref = appearance?.actions?.get(key) ?: return null
        return ref.takeIf { it.target.isNotBlank() }
    }

    /**
     * 该形象当前**实际可用**的别名（排序后，仅含可解析项）。
     *
     * 用途：`pet__play_action` 在别名非法时把可用清单回给模型（PRD US-2409 AC-4），
     * 以及设置页展示"这个形象支持哪些动作"。
     */
    fun availableAliases(appearance: PetAppearance?): List<String> {
        val actions = appearance?.actions ?: return emptyList()
        return actions.entries
            .filter { it.key.isNotBlank() && it.value.target.isNotBlank() }
            .map { it.key }
            .sorted()
    }

    /**
     * 该形象的待机别名（已过滤掉无法解析的项；去重保序）。
     *
     * @return 可安全随机挑选的待机别名列表（可能为空 → 调用方跳过待机动作）
     */
    fun idleAliases(appearance: PetAppearance?): List<String> {
        if (appearance == null) return emptyList()
        return appearance.idleAliases
            .mapNotNull { normalizeAlias(it) }
            .distinct()
            .filter { resolve(appearance, it) != null }
    }

    /**
     * 点击未命中 hit area 时应播放的别名。
     *
     * 回落顺序（PRD US-2404 AC-2）：形象配置的 [PetAppearance.tapAlias] → 待机候选首位 → null。
     */
    fun tapAlias(appearance: PetAppearance?): String? {
        val configured = normalizeAlias(appearance?.tapAlias)
        if (configured != null && resolve(appearance, configured) != null) return configured
        return idleAliases(appearance).firstOrNull()
    }
}
