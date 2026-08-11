package io.prism.ui.capabilities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.MemoryRecord
import io.prism.data.MemoryRepository
import io.prism.data.ProfileCategory
import io.prism.data.UserProfile
import io.prism.data.UserProfileRepository
import io.prism.memory.MemoryConfigRepository
import io.prism.memory.UserProfileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记忆管理 ViewModel（US-036，ADR-015 5.7）—— 暴露 L2/L3 记忆数据 + 提供查看/编辑/删除/一键清除操作。
 *
 * **职责**：
 * - 暴露 [memories]（L2 跨会话记忆列表）、[profiles]（L3 用户画像列表）、[windowSize]（L1 窗口大小 N）
 *   供 UI 订阅实时渲染
 * - 单条 L2 记忆删除 [deleteMemory]（经 [MemoryRepository.deleteById]）
 * - 单条 L3 画像编辑/删除（[selectProfileForEdit] / [saveProfile] / [deleteProfile]）
 * - L1 窗口大小 N 修改（[setWindowSize]，运行时动态生效，影响下一次 [io.prism.memory.SlidingWindowMemoryManager.processMessages]）
 * - 一键清除所有记忆（[showClearConfirm] / [hideClearConfirm] / [clearAll]），二次确认对话框
 *
 * **数据源策略**（同 [SkillsViewModel] 模式）：
 * - 主数据源：[MemoryRepository.memoryRecords] 与 [UserProfileRepository.profiles]（持久化层 StateFlow，
 *   实时响应删除/编辑）
 * - L1 配置：[MemoryConfigRepository.windowSize] Flow → stateIn 转为 StateFlow
 *
 * **可测性**（BR-testing-004）：
 * - 构造器仅依赖 4 个 Repository/Manager 接口，无 Android Context stub
 * - [Companion.validateWindowSize] 为 internal 纯函数，可在纯 JVM 测试中验证 N 边界
 * - [Companion.buildClearResultMessage] 为 internal 纯函数，将删除计数映射为 UI 文案
 *
 * **错误处理**（BR-error-handling-004）：
 * - 删除/保存失败不阻断 UI，记录日志 + 在 [uiMessage] 暴露错误文案（一次性消费）
 * - CancellationException 正确重抛（BR-error-handling-007）
 *
 * **GDPR 式控制权**（ADR-015 5.7）：用户对全部记忆数据有完全控制权，可单条删除或一键清除，
 * 与 PRD US-005「用户可查看/编辑/删除记忆，可一键清除」对齐。
 *
 * @param memoryRepository L2 跨会话记忆仓库（Phase A 已实现）
 * @param userProfileRepository L3 用户画像仓库（Phase A 已实现）
 * @param memoryConfigRepository L1 窗口大小配置仓库（Phase B 已实现）
 * @param userProfileManager L3 用户画像管理器（Phase D 已实现，提供 setExplicitPreference）
 */
class MemoryManagementViewModel(
    private val memoryRepository: MemoryRepository,
    private val userProfileRepository: UserProfileRepository,
    private val memoryConfigRepository: MemoryConfigRepository,
    private val userProfileManager: UserProfileManager
) : ViewModel() {

    /** L2 跨会话记忆列表（按 timestamp 升序，与 [MemoryRepository.memoryRecords] 一致）。 */
    val memories: StateFlow<List<MemoryRecord>> = memoryRepository.memoryRecords

    /** L3 用户画像列表（按 updatedAt 降序，与 [UserProfileRepository.profiles] 一致）。 */
    val profiles: StateFlow<List<UserProfile>> = userProfileRepository.profiles

    /**
     * L1 滑动窗口大小 N（US-032 AC-4 可配置，默认 10）。
     *
     * 经 [MemoryConfigRepository.windowSize] Flow → stateIn 转为 StateFlow，UI 修改时通过 [setWindowSize] 持久化。
     * 运行时动态生效：下一次 [io.prism.memory.SlidingWindowMemoryManager.processMessages] 调用读取最新值。
     */
    val windowSize: StateFlow<Int> = memoryConfigRepository.windowSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryConfigRepository.DEFAULT_WINDOW_SIZE)

    /** 当前选中的画像（用于编辑弹层）；null 表示弹层关闭。 */
    private val _selectedProfile = MutableStateFlow<UserProfile?>(null)
    val selectedProfile: StateFlow<UserProfile?> = _selectedProfile.asStateFlow()

    /** 是否展示"一键清除"二次确认对话框。 */
    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm.asStateFlow()

    /** 是否展示 L1 窗口大小编辑弹层。 */
    private val _showWindowSizeEditor = MutableStateFlow(false)
    val showWindowSizeEditor: StateFlow<Boolean> = _showWindowSizeEditor.asStateFlow()

    /**
     * 一次性 UI 消息（错误提示 / 成功反馈），UI 消费后调用 [consumeUiMessage] 清空。
     *
     * 用 sealed interface 表达，便于 UI 按类型分发渲染（错误用红色，成功用绿色）。
     */
    sealed interface UiMessage {
        data class Error(val text: String) : UiMessage
        data class Info(val text: String) : UiMessage
    }

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    /**
     * 删除单条 L2 跨会话记忆（US-036 AC-2）。
     *
     * @param id MemoryRecord id
     */
    fun deleteMemory(id: Long) {
        try {
            val deleted = memoryRepository.deleteById(id)
            _uiMessage.value = if (deleted) {
                UiMessage.Info("已删除记忆")
            } else {
                UiMessage.Error("记忆不存在或已被删除")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "deleteMemory failed: id=$id", e)
            _uiMessage.value = UiMessage.Error("删除记忆失败")
        }
    }

    /**
     * 选中画像进行编辑（或传入 null 关闭编辑弹层）。
     *
     * @param profile 待编辑的画像；null 关闭弹层；传入 `UserProfile(id=0, key="", value="")` 表示新建
     */
    fun selectProfileForEdit(profile: UserProfile?) {
        _selectedProfile.value = profile
    }

    /**
     * 保存画像（新建或更新，US-036 AC-3）。
     *
     * **逻辑**：
     * - key/value 校验非空（fail-fast）
     * - key/value 长度上限校验（M-2 修复，防止超长注入 systemPrompt 导致 token 溢出）
     * - 已有 id（编辑既有画像）：调用 [UserProfileRepository.update] 保留原 category
     * - 无 id（新建画像）：调用 [UserProfileManager.setExplicitPreference] 默认 category=EXPLICIT
     *
     * @param key 偏好键（非空，长度 ≤ [Companion.MAX_PROFILE_KEY_LEN]）
     * @param value 偏好值（非空，长度 ≤ [Companion.MAX_PROFILE_VALUE_LEN]）
     * @param existingId 既有画像 id（>0 表示编辑模式）；默认 0 表示新建
     */
    fun saveProfile(key: String, value: String, existingId: Long = 0L) {
        val validation = Companion.validateProfile(key, value)
        if (!validation.valid) {
            _uiMessage.value = UiMessage.Error(validation.message)
            return
        }
        val keyTrimmed = key.trim()
        val valueTrimmed = value.trim()
        try {
            if (existingId > 0L) {
                userProfileRepository.update(keyTrimmed, valueTrimmed)
                _uiMessage.value = UiMessage.Info("已更新偏好：$keyTrimmed")
            } else {
                userProfileManager.setExplicitPreference(keyTrimmed, valueTrimmed)
                _uiMessage.value = UiMessage.Info("已新增偏好：$keyTrimmed")
            }
            _selectedProfile.value = null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "saveProfile failed: key=$keyTrimmed", e)
            _uiMessage.value = UiMessage.Error("保存偏好失败")
        }
    }

    /**
     * 删除单条 L3 用户画像（US-036 AC-3）。
     *
     * @param key 偏好键
     */
    fun deleteProfile(key: String) {
        try {
            val deleted = userProfileManager.deleteProfile(key)
            _uiMessage.value = if (deleted) {
                UiMessage.Info("已删除偏好：$key")
            } else {
                UiMessage.Error("偏好不存在或已被删除")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "deleteProfile failed: key=$key", e)
            _uiMessage.value = UiMessage.Error("删除偏好失败")
        }
    }

    /**
     * 打开"一键清除"二次确认对话框（US-036 AC-4）。
     *
     * 仅当 [memories] 或 [profiles] 非空时打开，避免空态下无意义的确认弹层。
     */
    fun showClearConfirm() {
        if (memories.value.isEmpty() && profiles.value.isEmpty()) {
            _uiMessage.value = UiMessage.Info("当前无记忆可清除")
            return
        }
        _showClearConfirm.value = true
    }

    /** 关闭"一键清除"二次确认对话框（用户取消）。 */
    fun hideClearConfirm() {
        _showClearConfirm.value = false
    }

    /**
     * 一键清除所有记忆（L2 + L3，US-036 AC-4）。
     *
     * **事务边界**：L2 与 L3 在各自仓库内独立事务，不保证跨层原子性
     * （L2 删除成功后 L3 删除失败，L2 已不可恢复）。理由：
     * - L2/L3 是不同实体类型，无法在同一个 ObjectBox 事务中操作
     * - 用户语义上"清除所有记忆"是分级生效，部分成功仍满足"已清除"诉求
     * - 失败时通过 [uiMessage] 反馈具体哪层失败，用户可重试
     *
     * 调用后自动关闭确认弹层。
     */
    fun clearAll() {
        val memoryCount: Long
        val profileCount: Long
        try {
            memoryCount = memoryRepository.deleteAll()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "clearAll: L2 删除失败", e)
            _uiMessage.value = UiMessage.Error("清除跨会话记忆失败")
            _showClearConfirm.value = false
            return
        }
        try {
            profileCount = userProfileManager.deleteAllProfiles()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "clearAll: L3 删除失败", e)
            _uiMessage.value = UiMessage.Error("清除用户画像失败（跨会话记忆已清除 ${memoryCount} 条）")
            _showClearConfirm.value = false
            return
        }
        _uiMessage.value = UiMessage.Info(Companion.buildClearResultMessage(memoryCount, profileCount))
        _showClearConfirm.value = false
    }

    /**
     * 打开 L1 窗口大小编辑弹层（US-032 AC-4，US-036 记忆管理 UI 暴露入口）。
     */
    fun showWindowSizeEditor() {
        _showWindowSizeEditor.value = true
    }

    /** 关闭 L1 窗口大小编辑弹层（用户取消）。 */
    fun hideWindowSizeEditor() {
        _showWindowSizeEditor.value = false
    }

    /**
     * 修改 L1 滑动窗口大小 N（US-032 AC-4）。
     *
     * **校验**（[Companion.validateWindowSize]，纵深防御 BR-security-005）：
     * - N 在 [MemoryConfigRepository.MIN_WINDOW_SIZE]..[MemoryConfigRepository.MAX_WINDOW_SIZE] 范围内
     * - 防止 DataStore 被外部写入超大值导致下游 token 溢出
     *
     * @param n 新的窗口大小，必须在 1..50 范围内
     */
    fun setWindowSize(n: Int) {
        val validation = Companion.validateWindowSize(n)
        if (!validation.valid) {
            _uiMessage.value = UiMessage.Error(validation.message)
            return
        }
        viewModelScope.launch {
            try {
                memoryConfigRepository.setWindowSize(n)
                _uiMessage.value = UiMessage.Info("已设置窗口大小为 $n")
                _showWindowSizeEditor.value = false
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007
            } catch (e: Exception) {
                android.util.Log.w(TAG, "setWindowSize failed: n=$n", e)
                _uiMessage.value = UiMessage.Error("设置窗口大小失败")
            }
        }
    }

    /** UI 消费 [uiMessage] 后清空，避免旋转/重组时重复展示。 */
    fun consumeUiMessage() {
        _uiMessage.value = null
    }

    companion object {
        private const val TAG = "MemoryMgmtViewModel"

        /** 偏好键最大长度（M-2 修复，防止超长 key 注入 systemPrompt 导致 token 溢出）。 */
        internal const val MAX_PROFILE_KEY_LEN = 50

        /** 偏好值最大长度（M-2 修复，防止超长 value 注入 systemPrompt 导致 token 溢出）。 */
        internal const val MAX_PROFILE_VALUE_LEN = 500

        /**
         * 校验 L1 窗口大小 N 是否在合法范围（纯函数，BR-testing-004）。
         *
         * @param n 待校验值
         * @return [ValidationResult] 含 valid 标志与失败文案
         */
        internal fun validateWindowSize(n: Int): ValidationResult {
            if (n < MemoryConfigRepository.MIN_WINDOW_SIZE) {
                return ValidationResult(false, "窗口大小不能小于 ${MemoryConfigRepository.MIN_WINDOW_SIZE}")
            }
            if (n > MemoryConfigRepository.MAX_WINDOW_SIZE) {
                return ValidationResult(false, "窗口大小不能大于 ${MemoryConfigRepository.MAX_WINDOW_SIZE}（防止 token 溢出）")
            }
            return ValidationResult(true, "")
        }

        /**
         * 校验 L3 用户偏好 key/value 是否合法（纯函数，BR-testing-004，M-2 修复）。
         *
         * **校验项**：
         * - key/value trim 后非空
         * - key 长度 ≤ [MAX_PROFILE_KEY_LEN]
         * - value 长度 ≤ [MAX_PROFILE_VALUE_LEN]
         *
         * @param key 偏好键原始输入
         * @param value 偏好值原始输入
         * @return [ValidationResult] 含 valid 标志与失败文案
         */
        internal fun validateProfile(key: String, value: String): ValidationResult {
            val keyTrimmed = key.trim()
            val valueTrimmed = value.trim()
            if (keyTrimmed.isEmpty() || valueTrimmed.isEmpty()) {
                return ValidationResult(false, "偏好键和值均不能为空")
            }
            if (keyTrimmed.length > MAX_PROFILE_KEY_LEN) {
                return ValidationResult(false, "偏好键不能超过 $MAX_PROFILE_KEY_LEN 字符")
            }
            if (valueTrimmed.length > MAX_PROFILE_VALUE_LEN) {
                return ValidationResult(false, "偏好值不能超过 $MAX_PROFILE_VALUE_LEN 字符")
            }
            return ValidationResult(true, "")
        }

        /**
         * 将"一键清除"结果计数映射为 UI 文案（纯函数，BR-testing-004）。
         *
         * @param memoryCount 删除的 L2 记忆条数
         * @param profileCount 删除的 L3 画像条数
         * @return 展示给用户的成功文案
         */
        internal fun buildClearResultMessage(memoryCount: Long, profileCount: Long): String {
            return when {
                memoryCount == 0L && profileCount == 0L -> "无记忆需要清除"
                profileCount == 0L -> "已清除 $memoryCount 条跨会话记忆"
                memoryCount == 0L -> "已清除 $profileCount 条用户画像"
                else -> "已清除 $memoryCount 条跨会话记忆 · $profileCount 条用户画像"
            }
        }

        /** 供 viewModel() initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                MemoryManagementViewModel(
                    memoryRepository = app.memoryRepository,
                    userProfileRepository = app.userProfileRepository,
                    memoryConfigRepository = app.memoryConfigRepository,
                    userProfileManager = app.userProfileManager
                )
            }
        }
    }
}

/** [MemoryManagementViewModel.validateWindowSize] 返回类型。 */
data class ValidationResult(val valid: Boolean, val message: String)
