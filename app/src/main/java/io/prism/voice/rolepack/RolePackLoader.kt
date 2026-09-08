package io.prism.voice.rolepack

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 角色包「当前选中 id」持久化存储抽象（批次23-B，ADR-048）。
 *
 * 抽象目的：[RolePackLoader] 只依赖目录（[File]）+ 本接口，**不直接依赖 DataStore**——
 * JVM 单测可注入内存实现（项目测试基建无 Robolectric DataStore 基建）；生产环境由
 * [DataStoreRolePackSelectedIdStore]（DataStore，项目既有持久化风格）承载。
 */
interface RolePackSelectedIdStore {

    /** 当前选中角色包 id；未选中（出厂态）返回 null。 */
    suspend fun get(): String?

    /** 写入选中 id；传 null 清除选中（回落出厂 B0）。 */
    suspend fun set(id: String?)
}

/**
 * [RolePackSelectedIdStore] 的 DataStore 实现（批次23-B，ADR-048）。
 *
 * 对齐项目持久化风格（UserRulesRepository/ThinkingConfigRepository 同模式：独立
 * DataStore<Preferences>，key 级原子读写）。DataStore 单例由 PrismApplication 的
 * `preferencesDataStore` 委托保证（多实例会崩溃，仓库自身不创建 DataStore）。
 */
class DataStoreRolePackSelectedIdStore(
    private val dataStore: DataStore<Preferences>
) : RolePackSelectedIdStore {

    override suspend fun get(): String? = dataStore.data.first()[SELECTED_ID_KEY]

    override suspend fun set(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(SELECTED_ID_KEY)
            } else {
                prefs[SELECTED_ID_KEY] = id
            }
        }
    }

    companion object {
        /** DataStore key：当前选中角色包 id。 */
        val SELECTED_ID_KEY = stringPreferencesKey("rolepack_selected_id")
    }
}

/**
 * 批次23-B（ADR-048）：RolePack 角色包加载器 —— 用户私有目录（filesDir/rolepacks/）的
 * 导入/列举/删除/选中管理。
 *
 * **存储布局**：每个角色包一个 JSON 文件，文件名 = `sanitize(<pack.id>).json`
 * （以包 id 为准规范化，与显示名解耦；同名重复导入 = 同 id 覆盖更新，幂等）。
 * **文件内容 = 导入时的原始 JSON 原文**（未知字段原样保留，读取时再经 [RolePackParser]
 * 宽松解析——Schema 演进向前兼容）。
 *
 * **回落语义**（ADR-048 B2）：删除当前选中角色包 / 选中文件缺失 / 文件损坏 →
 * [effectiveRolePack] 回落出厂 B0（[RolePack.DEFAULT_ROLE_PACK]，通用人设），不抛异常。
 *
 * **目录注入可测性**：[baseDir] 为构造参数（生产传 `context.filesDir/rolepacks`），
 * 选中持久化经 [RolePackSelectedIdStore] 抽象——JVM 单测注入临时目录 + 内存 store，
 * 不依赖 Robolectric。
 *
 * **线程模型**：文件 IO 全部经 [withContext]（[Dispatchers.IO]）；选中读写随 store 实现
 * （DataStore 自身协程安全）。
 *
 * @param baseDir 角色包存储目录（生产：`File(context.filesDir, "rolepacks")`；不存在时导入自动创建）
 * @param selectedIdStore 选中 id 持久化（生产：[DataStoreRolePackSelectedIdStore]）
 */
class RolePackLoader(
    private val baseDir: File,
    private val selectedIdStore: RolePackSelectedIdStore
) {

    /**
     * 列举全部已导入角色包（按显示名排序）。
     * 单个文件解析失败（损坏/被外部改动）时**跳过该文件**（不抛——列表页可用性优先；
     * 损坏文件可经 [remove] 清除或重新导入覆盖）。
     */
    suspend fun list(): List<RolePack> = withContext(Dispatchers.IO) {
        packFiles()
            .mapNotNull { f ->
                runCatching { RolePackParser.parse(f.readText()) }
                    .onFailure { Log.w(TAG, "skip malformed rolepack file=${f.name}: ${it::class.simpleName}") }
                    .getOrNull()
            }
            .sortedBy { it.name }
    }

    /**
     * 导入角色包（解析校验通过才落盘）。
     *
     * @param name 来源文件名（如 "myrole.rolepack"）——仅用于日志可读性；**存储文件名
     *   以解析出的包 id 规范化为准**（见类 KDoc 存储布局）
     * @param json 角色包 JSON 全文
     * @return 解析后的 [RolePack]
     * @throws RolePackParseException JSON 畸形 / schema 版本不支持 / id 为出厂保留值
     *   （[RolePack.DEFAULT_ID]，防用户包与内置角色语义冲突；不落盘）
     */
    suspend fun import(name: String, json: String): RolePack = withContext(Dispatchers.IO) {
        // 先解析校验再落盘：畸形包绝不进用户目录（fail-fast，导入方直接拿到可读错误）
        val pack = RolePackParser.parse(json)
        if (pack.id == RolePack.DEFAULT_ID) {
            throw RolePackParseException(
                "RolePack id \"${RolePack.DEFAULT_ID}\" 为出厂内置角色保留值，请改用其它 id"
            )
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val file = File(baseDir, safeFileName(pack.id))
        file.writeText(json)
        Log.i(TAG, "rolepack imported: name=$name id=${pack.id} file=${file.name}")
        pack
    }

    /**
     * 删除指定 id 的角色包文件。
     *
     * @return 是否删除了文件（不存在返回 false）；**若删除的是当前选中包，选中态同步清除**
     *   （下次 [effectiveRolePack] 回落 B0——删除自动回落语义）
     */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        val target = findPackFile(id)
        val removed = target != null && target.delete()
        if (removed) {
            Log.i(TAG, "rolepack removed: id=$id")
            if (selectedIdStore.get() == id) {
                selectedIdStore.set(null)
            }
        }
        removed
    }

    /** 选中角色包（热切换：下次装配读 [effectiveRolePack] 即生效）。 */
    suspend fun select(id: String) {
        selectedIdStore.set(id)
    }

    /** 清除选中（回落出厂 B0）。 */
    suspend fun clearSelection() {
        selectedIdStore.set(null)
    }

    /** 当前选中的角色包 id（未选中返回 null）。 */
    suspend fun selectedId(): String? = selectedIdStore.get()

    /**
     * 当前生效角色包 = 用户选中并成功解析的角色包，否则出厂 B0。
     *
     * **装配点读取入口**：ConversationViewModel（persona 注入）与 VoiceSessionController
     * （voice.system / style 分段）每次装配调用一次；文件数少（用户导入量级），逐文件解析
     * 开销可忽略。选中文件缺失/损坏 → 回落 [RolePack.DEFAULT_ROLE_PACK]（不抛）。
     */
    suspend fun effectiveRolePack(): RolePack {
        val id = selectedIdStore.get() ?: return RolePack.DEFAULT_ROLE_PACK
        return withContext(Dispatchers.IO) {
            findPackFile(id)?.let { f ->
                runCatching { RolePackParser.parse(f.readText()) }
                    .onFailure { Log.w(TAG, "selected rolepack unreadable, fallback B0: ${it::class.simpleName}") }
                    .getOrNull()
            } ?: RolePack.DEFAULT_ROLE_PACK
        }
    }

    /** 目录下全部候选文件（跳过子目录；扩展名不限——.rolepack/.json 均可由内容识别）。 */
    private fun packFiles(): List<File> =
        baseDir.listFiles()?.filter { it.isFile } ?: emptyList()

    /** 按 id 定位角色包文件（逐文件宽松解析比对；损坏文件自然不匹配）。 */
    private fun findPackFile(id: String): File? =
        packFiles().firstOrNull { f ->
            runCatching { RolePackParser.parse(f.readText()) }.getOrNull()?.id == id
        }

    /**
     * id/来源名 → 安全文件名（防路径穿越：仅保留字母/数字/点/下划线/连字符，
     * 其余替换为 `_`；结果空时回退 `rolepack`；统一追加 .json 扩展名）。
     */
    private fun safeFileName(raw: String): String {
        val safe = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        val base = safe.ifBlank { "rolepack" }
        return "$base.json"
    }

    companion object {
        private const val TAG = "RolePackLoader"
    }
}
