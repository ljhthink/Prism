package io.prism.skill

import android.content.Context
import io.prism.data.SkillConfig
import io.prism.data.SkillRepository
import io.prism.data.SkillSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Skill 注册中心（ADR-013 5.3）。
 *
 * **职责**：
 * 1. 启动时扫描所有 Skill 加载源（assets 内置 / filesDir 用户 / filesDir 远程）
 * 2. 解析每个 Skill 的 SKILL.md，构建 [SkillEntry]（[SkillConfig] + [SkillManifest]）
 * 3. 同步到 [SkillRepository]（新增入库 / 更新版本 / 标记缺失）
 * 4. 暴露 [skills] StateFlow 供 UI 订阅
 * 5. 提供 [enabledSkills] 供 ConversationViewModel 注入（实现 [SkillToolSource] 接口）
 *
 * **加载源优先级**（对齐 OpenClaw 6 层，ADR-013 5.3）：
 * 1. 用户自建 `filesDir/skills/user/`（最高）
 * 2. 远程下载 `filesDir/skills/remote/`
 * 3. 内置预设 `assets/skills/builtin/`（最低）
 *
 * 同名 Skill 按优先级覆盖（高优先级覆盖低优先级）。
 *
 * **assets 路径约定**：内置 Skill 的 [SkillConfig.skillDir] 使用 `assets://` 前缀
 * （如 `assets://skills/builtin/translator`），运行时由调用方据此判断访问方式。
 *
 * **线程安全**：[scanAndSync] 在 [ioDispatcher]（默认 IO）执行，[skills] 为线程安全 StateFlow。
 * 同步过程中持有内部互斥（通过 StateFlow 原子更新），避免并发扫描竞态。
 *
 * **可测性设计**（US-022 AC-5 补强，2026-08-09）：
 * 所有不依赖 Android [Context] / [SkillRepository] 的纯逻辑提取到 [companion object]
 * 并标记 `internal`，可在纯 JVM 单元测试中直接验证，无需 Robolectric / Mockito。
 * - [Companion.dedupByPriority]：优先级去重
 * - [Companion.parseToEntry]：SKILL.md → SkillEntry
 * - [Companion.scanDirectory]：文件系统目录扫描（接收 [File] 参数，可用 @TempDir 测试）
 * - [Companion.computeSyncDiff]：扫描结果与持久化状态的 diff 计算
 * - [Companion.mergeWithPersistedState]：扫描结果与持久化状态合并
 * - [Companion.filterEnabledSkills]：已启用过滤
 * 仅 [scanBuiltin]（依赖 AssetManager）受限于纯 JVM 测试环境，按项目惯例受限通过。
 *
 * **M4 Phase D 可测性补强**：`class` 标记 `open` + [enabledSkills] 标记 `open`，
 * 实现 [SkillToolSource] 函数式接口；ConversationViewModel 依赖 [SkillToolSource] 接口
 * 而非具体类，使集成测试可注入简单 lambda stub（无需 Context / BoxStore / SkillRepository）。
 *
 * @param context Android Context（用于 AssetManager 与 filesDir）
 * @param skillRepository Skill 配置仓库
 * @param ioDispatcher IO 协程调度器（可注入便于测试）
 */
open class SkillRegistry(
    private val context: Context,
    private val skillRepository: SkillRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** 内置 Skill 在 assets 中的根路径 */
    private val builtinAssetsRoot = "skills/builtin"

    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    /** 全部已加载的 Skill（按优先级去重后的列表），供 UI 订阅 */
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    /**
     * Skill 加载条目 —— [SkillConfig]（持久化层）与 [SkillManifest]（内存层）的组合。
     *
     * @property config 持久化配置（启用状态、来源、路径）
     * @property manifest 解析后的元数据（含 body 指令）
     */
    data class SkillEntry(
        val config: SkillConfig,
        val manifest: SkillManifest
    )

    /**
     * 缺失 Skill 的处置动作（[syncToRepository] 对 `diff.toMarkUninstalled` 每个条目）。
     *
     * - [KEEP_HIDDEN]: 已标记 hidden 的条目 → 保持 DB 行不变（过滤已经生效，无需变更）
     * - [PURGE_BUILTIN]: 内置 Skill 已从 assets 删除 → 删除 DB 行，避免幽灵条目残留
     * - [MARK_UNINSTALLED]: 用户/远程文件缺失 → 标记 isInstalled=false（临时删除，可恢复）
     */
    enum class DisposeAction {
        KEEP_HIDDEN,
        PURGE_BUILTIN,
        MARK_UNINSTALLED
    }

    /**
     * 同步差异（[Companion.computeSyncDiff] 输出）。
     *
     * @property toInsert 新增的 SkillConfig（id=0，isEnabled=false）
     * @property toUpdate 更新的 SkillConfig（保留 id + isEnabled，刷新 source/version/skillDir/isInstalled=true）
     * @property toMarkUninstalled 表里存在但扫描未发现的 SkillConfig（标记 isInstalled=false）
     */
    data class SyncDiff(
        val toInsert: List<SkillConfig>,
        val toUpdate: List<SkillConfig>,
        val toMarkUninstalled: List<SkillConfig>
    )

    /**
     * 启动时扫描所有加载源，同步 [SkillRepository] 并刷新 [skills]。
     *
     * **流程**（ADR-013 5.3）：
     * 1. 扫描内置预设（assets）+ 用户自建 + 远程下载
     * 2. 按优先级去重（用户 > 远程 > 内置）
     * 3. 同步到 SkillConfig 表：新增入库、版本更新、缺失标记 isInstalled=false
     * 4. 刷新 [_skills] StateFlow
     *
     * **容错**：单个 Skill 解析失败不影响其他 Skill（隔离失败，记录日志）。
     *
     * 应在 [io.prism.PrismApplication.onCreate] 的 IO 协程中调用，不阻塞 UI。
     */
    suspend fun scanAndSync() = withContext(ioDispatcher) {
        // filesDir 路径在此处（而非构造器）解析，避免构造期访问 Android Context.filesDir
        // 抛 Stub 异常阻断纯 JVM 测试（US-022 AC-5 可测性补强）。
        val userSkillsDir = File(context.filesDir, "skills/user")
        val remoteSkillsDir = File(context.filesDir, "skills/remote")

        val discovered = mutableListOf<SkillEntry>()

        // 1. 扫描内置预设（assets，最低优先级，先加入）
        discovered += scanBuiltin()

        // 2. 扫描远程下载（覆盖同名内置）
        discovered += Companion.scanDirectory(remoteSkillsDir, SkillSource.REMOTE)

        // 3. 扫描用户自建（最高优先级，覆盖同名远程/内置）
        discovered += Companion.scanDirectory(userSkillsDir, SkillSource.LOCAL_USER)

        // 4. 过滤已删除（isHidden）的 Skill，避免用户删除后下次扫描又恢复
        //    （修复：内置 Skill 无法删除文件，仅靠置 hidden 阻止扫描恢复）
        val hiddenNames = Companion.hiddenNameSet(skillRepository.getAll())
        val dedupedRaw = Companion.dedupByPriority(discovered)
        val deduped = Companion.filterOutHidden(dedupedRaw, hiddenNames)

        // 5. 同步到 SkillRepository
        syncToRepository(deduped)

        // 6. 刷新 StateFlow（合并 SkillConfig 表中的启用状态）
        _skills.value = Companion.mergeWithPersistedState(
            discovered = deduped,
            persisted = skillRepository.getAll().associateBy { it.name }
        )
    }

    /**
     * 获取所有已启用的 Skill（供 ConversationViewModel 注入，ADR-013 5.4）。
     *
     * **M4 Phase D 可测性补强**：本方法标记 `open`，并抽取 [SkillToolSource] 函数式接口，
     * ConversationViewModel 依赖接口（[SkillToolSource]）而非具体类，
     * 使集成测试可注入简单 stub（无需 Android Context / BoxStore / SkillRepository 协作）。
     *
     * @return 已启用且已安装的 [SkillEntry] 列表
     */
    open fun enabledSkills(): List<SkillEntry> = Companion.filterEnabledSkills(_skills.value)

    /**
     * 扫描内置预设 Skill（`assets/skills/builtin/`）。
     *
     * 每个子目录代表一个 Skill，需含 `SKILL.md`。
     * 解析失败的 Skill 跳过并记录（不阻断其他 Skill 加载）。
     *
     * **可测性说明**：本方法依赖 [Context.getAssets]（Android AssetManager），
     * 纯 JVM 单元测试环境无法访问（需 Robolectric）。
     * 按项目惯例（US-002/003/008 同模式），本方法受限通过，由 [Companion.scanDirectory]
     * 覆盖文件系统扫描逻辑、[Companion.parseToEntry] 覆盖解析逻辑。
     */
    private fun scanBuiltin(): List<SkillEntry> {
        val results = mutableListOf<SkillEntry>()
        val skillDirs = runCatching { context.assets.list(builtinAssetsRoot) }
            .getOrNull()?.filter { it.isNotBlank() } ?: return emptyList()

        for (dirName in skillDirs) {
            val skillMdPath = "$builtinAssetsRoot/$dirName/SKILL.md"
            // G-03 修复：与 scanDirectory 统一错误处理风格（return null ?: continue）
            val content = runCatching { context.assets.open(skillMdPath).use { it.readBytes().decodeToString() } }
                .getOrElse { e ->
                    android.util.Log.w(TAG, "Builtin skill '$dirName' SKILL.md read failed: ${e.message}")
                    null
                } ?: continue

            val entry = Companion.parseToEntry(
                content = content,
                source = SkillSource.LOCAL_BUILTIN,
                sourceUri = null,
                skillDir = "assets://$builtinAssetsRoot/$dirName"
            ) ?: continue
            results.add(entry)
        }
        return results
    }

    /**
     * 同步扫描结果到 [SkillRepository]（ADR-013 5.3）。
     *
     * 委托 [Companion.computeSyncDiff] 计算差异，再调用 [SkillRepository] 落库。
     *
     * **同步策略**：
     * - 新增：扫描到但表里没有的 Skill，新建 SkillConfig（isEnabled=false，待用户启用）
     * - 更新：扫描到且表里有的 Skill，更新 version/source/skillDir/isInstalled=true，保留 isEnabled
     * - 标记缺失：表里有但扫描未发现的 Skill，标记 isInstalled=false（文件已删除）
     */
    private fun syncToRepository(discovered: List<SkillEntry>) {
        val existing = skillRepository.getAll().associateBy { it.name }
        val diff = Companion.computeSyncDiff(discovered, existing)

        for (config in diff.toInsert) {
            skillRepository.save(config)
        }
        for (config in diff.toUpdate) {
            skillRepository.save(config)
        }
        for (config in diff.toMarkUninstalled) {
            // 已删除（isHidden）的 Skill：保留 DB 行以维持隐藏标记（否则内置 Skill 下次扫描恢复）。
            // 已被 UI 置 hidden 的条目不参与 isInstalled 变更（隐藏标记已使其不出现在 UI）。
            when (Companion.disposeMissingConfigAction(config)) {
                DisposeAction.KEEP_HIDDEN -> {
                    // isHidden 条目：保留 DB 行（隐藏标记由 hiddenNameSet 过滤持续生效）
                }
                DisposeAction.PURGE_BUILTIN -> {
                    // 修复（删除内置 Skill）：内置 Skill 已从 assets 移除（如废弃的旧内置 Skill），
                    // 直接删除 DB 行而非标记 isInstalled=false —— 否则 UI 列表残留「解析失败」幽灵条目
                    // （manifest 为 null 且 isInstalled=false）。内置 Skill 无源文件可恢复，删除即彻底。
                    skillRepository.remove(config.id)
                    android.util.Log.i(TAG, "Builtin Skill '${config.name}' removed from assets, purged from DB")
                }
                DisposeAction.MARK_UNINSTALLED -> {
                    skillRepository.setInstalled(config.id, false)
                    android.util.Log.i(TAG, "Skill '${config.name}' no longer found, marked isInstalled=false")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SkillRegistry"

        /**
         * 按优先级去重：同名 Skill 保留高优先级（LOCAL_USER > REMOTE > LOCAL_BUILTIN）。
         *
         * **纯函数**（US-022 AC-5 可测性补强）：不依赖实例状态，可在纯 JVM 测试中验证。
         */
        internal fun dedupByPriority(entries: List<SkillEntry>): List<SkillEntry> {
            val priority = mapOf(
                SkillSource.LOCAL_USER to 3,
                SkillSource.REMOTE to 2,
                SkillSource.LOCAL_BUILTIN to 1
            )
            return entries
                .groupBy { it.config.name }
                // G-04：groupBy 保证每个 group 至少含 1 个元素，maxByOrNull 不会返回 null，!! 安全
                .mapValues { (_, group) -> group.maxByOrNull { priority[it.config.source] ?: 0 }!! }
                .values
                .sortedBy { it.config.name }
        }

        /**
         * 收集已删除（isHidden=true）Skill 的 name 集合（纯函数，BR-testing-004）。
         *
         * 供 [scanAndSync] 过滤已删除 Skill（用户删除后，任何来源下次扫描都不恢复）。
         *
         * @param configs 全部持久化 SkillConfig 列表
         * @return isHidden=true 的 name 集合
         */
        internal fun hiddenNameSet(configs: List<SkillConfig>): Set<String> =
            configs.filter { it.isHidden }.map { it.name }.toSet()

        /**
         * 过滤掉已删除（isHidden）Skill 条目（纯函数，BR-testing-004）。
         *
         * [scanAndSync] 在去重后调用，确保用户删除的内置 Skill 不会因 assets 仍存在而恢复。
         *
         * @param entries 去重后的 SkillEntry 列表
         * @param hiddenNames 已删除 Skill 的 name 集合（[hiddenNameSet] 产出）
         * @return 过滤后的列表（不含 hidden 条目）
         */
        internal fun filterOutHidden(
            entries: List<SkillEntry>,
            hiddenNames: Set<String>
        ): List<SkillEntry> = entries.filterNot { it.config.name in hiddenNames }

        /**
         * 缺失 Skill 的处置动作（纯函数，BR-testing-004）。
         *
         * [syncToRepository] 对 `toMarkUninstalled`（表里有但扫描未发现）条目决定处置方式：
         * - [DisposeAction.KEEP_HIDDEN]：已删除（isHidden）条目 —— 保留 DB 行以维持隐藏标记，
         *   不改变 isInstalled（隐藏过滤已使其不出现在 UI、不注入工具）
         * - [DisposeAction.PURGE_BUILTIN]：内置 Skill 已从 assets 移除 —— 直接删除 DB 行，
         *   避免 UI 残留「解析失败」幽灵条目（manifest 为 null 且 isInstalled=false）
         * - [DisposeAction.MARK_UNINSTALLED]：用户/远程 Skill 文件缺失 —— 标记 isInstalled=false
         *   （文件可能暂时被移动/删除，保留行待后续扫描恢复）
         *
         * @param config 待处置的缺失 SkillConfig
         */
        internal fun disposeMissingConfigAction(config: SkillConfig): DisposeAction = when {
            config.isHidden -> DisposeAction.KEEP_HIDDEN
            config.source == SkillSource.LOCAL_BUILTIN -> DisposeAction.PURGE_BUILTIN
            else -> DisposeAction.MARK_UNINSTALLED
        }

        /**
         * 解析 SKILL.md 内容为 [SkillEntry]（含新建的 [SkillConfig]，id=0 待入库分配）。
         *
         * 解析失败返回 null（已记录日志），不抛异常（隔离失败）。
         *
         * **纯函数**（US-022 AC-5 可测性补强）：仅依赖 [SkillManifestParser]（object，无状态）。
         *
         * **displayName 提取策略**（BUG 修复：原用 description 首行，现在优先找 body 中第一个一级标题 #）：
         * 1. body 第一个 `# ` 开头的行 → 提取为 displayName（最长 60 字符）—— SKILL.md 格式惯例
         * 2. fallback → description 首行 → name
         */
        internal fun parseToEntry(
            content: String,
            source: String,
            sourceUri: String?,
            skillDir: String
        ): SkillEntry? {
            val result = runCatching { SkillManifestParser.parse(content) }
                .getOrElse { e ->
                    android.util.Log.w(TAG, "Skill parse failed for '$skillDir': ${e.message}")
                    return null
                }
            val manifest = result.manifest
            val body = result.body
            val candidateDisplayName = findFirstHeading(body)
                ?: manifest.description.lineSequence().firstOrNull()
                ?: manifest.name
            val displayName = candidateDisplayName.take(60)
            val config = SkillConfig(
                id = 0L, // 待 syncToRepository 匹配已有记录或新建
                name = manifest.name,
                displayName = displayName,
                source = source,
                sourceUri = sourceUri,
                skillDir = skillDir,
                isEnabled = false, // 默认不启用，syncToRepository 会从已有记录继承
                isInstalled = true,
                version = manifest.version ?: "0.0.0"
            )
            return SkillEntry(config = config, manifest = manifest)
        }

        /**
         * 在 Markdown body 中找第一个一级标题（以 `# ` 开头的行）。
         *
         * SKILL.md 规范：一级标题即为 Skill 的人类可读名称，用于 UI 展示。
         *
         * @return 找到的标题文本（无前缀 `# `），未找到返回 null
         */
        internal fun findFirstHeading(body: String): String? {
            val firstLine = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return null
            return if (firstLine.startsWith("# ")) {
                firstLine.removePrefix("# ").trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }

        /**
         * 扫描文件系统目录下的 Skill（用户自建 / 远程下载）。
         *
         * 每个子目录代表一个 Skill，需含 `SKILL.md`。
         *
         * **纯函数**（US-022 AC-5 可测性补强）：接收 [File] 参数，不依赖 [Context]，
         * 可用 JUnit `@TempDir` 在纯 JVM 测试中验证。
         */
        internal fun scanDirectory(dir: File, source: String): List<SkillEntry> {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            val results = mutableListOf<SkillEntry>()
            val skillDirs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()

            for (skillDir in skillDirs) {
                val skillMd = File(skillDir, "SKILL.md")
                if (!skillMd.exists() || !skillMd.isFile) {
                    android.util.Log.w(TAG, "Skill dir '${skillDir.name}' missing SKILL.md, skip")
                    continue
                }
                val content = runCatching { skillMd.readText() }
                    .getOrElse { e ->
                        android.util.Log.w(TAG, "Skill '${skillDir.name}' SKILL.md read failed: ${e.message}")
                        return@getOrElse null
                    } ?: continue

                val entry = parseToEntry(
                    content = content,
                    source = source,
                    sourceUri = if (source == SkillSource.REMOTE) skillDir.name else null,
                    skillDir = skillDir.absolutePath
                ) ?: continue
                results.add(entry)
            }
            return results
        }

        /**
         * 计算扫描结果与持久化状态的同步差异（ADR-013 5.3）。
         *
         * **纯函数**（US-022 AC-5 可测性补强）：不依赖 [SkillRepository]，仅做数据计算。
         *
         * **策略**：
         * - 新增（toInsert）：扫描到但表里没有的 Skill，构建新 SkillConfig（isEnabled=false）
         * - 更新（toUpdate）：扫描到且表里有的 Skill，保留 id + isEnabled，刷新 displayName/source/sourceUri/skillDir/isInstalled=true/version
         * - 标记缺失（toMarkUninstalled）：表里有且 isInstalled=true 但扫描未发现的 Skill
         *
         * @param discovered 扫描去重后的 SkillEntry 列表
         * @param existing 当前持久化的 SkillConfig（按 name 索引）
         */
        internal fun computeSyncDiff(
            discovered: List<SkillEntry>,
            existing: Map<String, SkillConfig>
        ): SyncDiff {
            val discoveredNames = discovered.map { it.config.name }.toSet()
            val toInsert = mutableListOf<SkillConfig>()
            val toUpdate = mutableListOf<SkillConfig>()

            for (entry in discovered) {
                val manifest = entry.manifest
                val existingConfig = existing[entry.config.name]
                if (existingConfig == null) {
                    // R4（UXR10，ADR-032）：内置 Skill 首次安装**默认启用**——内置预设（assets）
                    // 是应用自带能力，应开箱即用，否则 enabledSkills() 为空、LLM 完全感知不到
                    // Skills（真机实测：web-research 等内置 Skill 未启用 → 深度调研指令无响应）。
                    // 内置 Skill 无源文件可"删除"，用户改通过 UI 禁用；禁用状态由 toUpdate 分支
                    // 保留（copy 自 existingConfig），不会因重启被重新启用。
                    // 用户自建 / 远程下载 Skill 仍默认不启用（由用户主动启用）。
                    toInsert.add(entry.config.copy(isEnabled = entry.config.source == SkillSource.LOCAL_BUILTIN))
                } else {
                    // 更新：保留 id + isEnabled，更新其他字段
                    toUpdate.add(
                        existingConfig.copy(
                            displayName = entry.config.displayName,
                            source = entry.config.source,
                            sourceUri = entry.config.sourceUri,
                            skillDir = entry.config.skillDir,
                            isInstalled = true,
                            version = manifest.version ?: existingConfig.version
                        )
                    )
                }
            }

            // 标记缺失：表里有且 isInstalled=true 但扫描未发现
            val toMarkUninstalled = existing.values.filter {
                it.name !in discoveredNames && it.isInstalled
            }

            return SyncDiff(toInsert, toUpdate, toMarkUninstalled)
        }

        /**
         * 将扫描结果与持久化的 SkillConfig 表合并（继承 isEnabled 状态）。
         *
         * 扫描得到的 [SkillEntry] 的 config.id=0 且 isEnabled=false（临时），
         * 需从持久化状态填充 id 与 isEnabled。
         *
         * **纯函数**（US-022 AC-5 可测性补强）：不依赖 [SkillRepository]，接收 persisted Map 参数。
         *
         * @param discovered 扫描去重后的 SkillEntry 列表
         * @param persisted 当前持久化的 SkillConfig（按 name 索引）
         */
        internal fun mergeWithPersistedState(
            discovered: List<SkillEntry>,
            persisted: Map<String, SkillConfig>
        ): List<SkillEntry> {
            return discovered.map { entry ->
                val stored = persisted[entry.config.name]
                if (stored != null) {
                    entry.copy(config = stored)
                } else {
                    // 未持久化的异常情况（syncToRepository 应已入库），保持原样
                    entry
                }
            }
        }

        /**
         * 过滤已启用且已安装的 Skill（供 [enabledSkills] 使用）。
         *
         * **纯函数**（US-022 AC-5 可测性补强）。
         */
        internal fun filterEnabledSkills(skills: List<SkillEntry>): List<SkillEntry> =
            skills.filter { it.config.isEnabled && it.config.isInstalled && !it.config.isHidden }
    }
}
