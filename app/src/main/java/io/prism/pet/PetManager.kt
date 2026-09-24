package io.prism.pet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.prism.pet.asset.PetPackException
import io.prism.pet.asset.PetPackInstaller
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetConfig
import io.prism.pet.overlay.PetOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批次24（ADR-051）：桌宠门面 —— 应用级单例，设置页与悬浮窗服务之间的唯一协调者。
 *
 * **职责边界**（刻意做窄，避免变成"上帝对象"）：
 * - 形象资产：导入（`.petpack` / 单图）、列举、删除（委托 [PetPackInstaller]）；
 * - 配置读写：委托 [PetConfigRepository]；
 * - 生命周期：按 `enabled` 起停 [PetOverlayService]（**只做 Intent 投递，不做窗口操作**）；
 * - 渲染描述符：把 [PetAppearance] + [PetConfig] 编译成渲染器可吃的 JSON。
 *
 * **不负责**：窗口布局、触摸手势、WebView 生命周期——这些留在服务与渲染器里。
 *
 * **线程模型**：所有 IO 走 [scope]（[Dispatchers.IO] 为默认，可注入以便测试确定性）。
 *
 * @param context 应用上下文（**必须传 applicationContext**；本类生命周期与进程一致）
 * @param installer 形象安装器（生产由 `PrismApplication` 构造，目录为 `filesDir/pets`）
 * @param configRepository 配置仓库
 * @param scope 协程作用域（缺省 [Dispatchers.IO] 上的常驻作用域）
 */
class PetManager(
    private val context: Context,
    private val installer: PetPackInstaller,
    private val configRepository: PetConfigRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** 悬浮窗运行态（设置页据此显示"已开启/未开启/失败"）。 */
    enum class RuntimeState {
        /** 开关关闭，或权限未授予。 */
        STOPPED,

        /** 服务已启动、窗口已添加。 */
        RUNNING,

        /**
         * 上次启动失败（无权限 / 前台服务被拒 / 渲染器报错）。
         * @see lastError
         */
        FAILED
    }

    private val _state = MutableStateFlow(RuntimeState.STOPPED)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** 最近一次失败的可读原因（设置页展示；无失败为 null）。**不含路径**。 */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _appearances = MutableStateFlow<List<PetAppearance>>(emptyList())

    /** 已安装形象列表（设置页观察）。 */
    val appearances: StateFlow<List<PetAppearance>> = _appearances.asStateFlow()

    private val petsDir: File = File(context.filesDir, PETS_DIR)

    init {
        scope.launch { refreshAppearances() }
        // 注：**恢复悬浮窗不放在这里**。放在 init 会与
        // `PrismApplication.onCreate` 的显式调用重复（真机实测日志出现两次
        // "restoring pet overlay"）。恢复由应用启动流程显式发起，单一来源。
    }

    /**
     * 按持久化的开关状态恢复悬浮窗（进程启动时调用）。
     *
     * **为什么这是正确的**（与 ADR-041 的"隐形常驻"边界不冲突）：`enabled=true`
     * 是用户**显式打开**过的意图；恢复出来的窗口在桌面**可见**、通知也在、随时可关。
     * 这不是"看不见的后台任务"。（ADR-051 D7）
     *
     * @return 是否发起了恢复
     */
    suspend fun restoreIfEnabled(): Boolean {
        // 不用 runCatching 包 suspend（BR-error-handling-007：会吞 CancellationException）
        val enabled = try {
            configRepository.get().enabled
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "restore pet: config read failed (${e::class.simpleName})")
            false
        }
        if (!enabled) return false
        Log.i(TAG, "restoring pet overlay (persisted enabled=true)")
        // guardrail D-4：恢复被系统拒绝时必须让用户看到原因，而不是静默保持"已停止"
        if (!PetOverlayService.start(context)) {
            onServiceFailed("系统拒绝了桌宠恢复（后台启动受限），请在应用内重新打开桌宠")
            return false
        }
        return true
    }

    // ==================== 状态 ====================

    /** 由 [PetOverlayService] 回调，标记运行态。 */
    fun onServiceStarted() {
        _state.value = RuntimeState.RUNNING
        _lastError.value = null
    }

    /** 由 [PetOverlayService] 回调（用户关闭开关 / 服务被停止 / 权限被回收）。 */
    fun onServiceStopped() {
        // **不区分上一状态**（guardrail R3-A 的 N-1）：早期实现在 FAILED 下不复位，
        // 于是"看门狗误报 FAILED → 服务已停"会留下一个与事实不符的失败态，
        // 用户即便重新打开开关，设置页也可能先显示旧的失败原因。
        // 服务停了就是停了，"上次失败原因"由 lastError 单独承载。
        _state.value = RuntimeState.STOPPED
    }

    /** 由 [PetOverlayService] 回调，报告可读失败原因。 */
    fun onServiceFailed(reason: String) {
        _state.value = RuntimeState.FAILED
        _lastError.value = reason
        Log.w(TAG, "pet overlay failed: $reason")
    }

    // ==================== 开关 ====================

    /**
     * 打开/关闭桌宠。
     *
     * **只投递 Intent**：真正的窗口操作与失败处理在服务侧完成，服务通过
     * [onServiceStarted] / [onServiceFailed] 回报。这样"开关"与"窗口"之间没有隐藏状态机。
     */
    suspend fun setEnabled(enabled: Boolean) {
        configRepository.setEnabled(enabled)
        if (enabled) {
            _lastError.value = null
            // guardrail D-4：start 返回 false 时给出可读原因（此前失败只进 logcat）
            if (!PetOverlayService.start(context)) {
                onServiceFailed("系统拒绝了桌宠启动（后台启动受限），请保持应用在前台后重试")
            }
        } else {
            PetOverlayService.stop(context)
            _state.value = RuntimeState.STOPPED
        }
    }

    /** 窗口拖动结束后写入归一化位置（仅在变化显著时落盘，防高频抖动写盘）。 */
    suspend fun updatePosition(x: Float, y: Float) {
        val current = configRepository.get()
        val next = current.copy(posX = x, posY = y).clamped()
        if (next.positionDiffersFrom(current)) {
            configRepository.setPosition(next.posX, next.posY)
        }
    }

    /** 重置到出厂默认位置（右下角）。 */
    suspend fun resetPosition() {
        configRepository.setPosition(PetConfig.DEFAULT.posX, PetConfig.DEFAULT.posY)
        refreshOverlay()
    }

    suspend fun setScale(scale: Float) {
        configRepository.setScale(scale)
        refreshOverlay()
    }

    suspend fun setOpacity(opacity: Float) {
        configRepository.setOpacity(opacity)
        refreshOverlay()
    }

    suspend fun setIdleEnabled(enabled: Boolean) {
        configRepository.setIdleEnabled(enabled)
        refreshOverlay()
    }

    suspend fun setReduceMotion(reduce: Boolean) {
        configRepository.setReduceMotion(reduce)
        refreshOverlay()
    }

    suspend fun setLowPowerMode(enabled: Boolean) {
        configRepository.setLowPowerMode(enabled)
        refreshOverlay()
    }

    suspend fun setBubbleEnabled(enabled: Boolean) {
        configRepository.setBubbleEnabled(enabled)
        // 也必须刷新：`showBubble` 读的是服务内的 config **快照**，
        // 不刷新会让"关掉气泡"在本次运行内不生效（要重启桌宠才生效）。
        refreshOverlay()
    }

    suspend fun setExpressionResetSeconds(seconds: Int) {
        configRepository.setExpressionResetSeconds(seconds)
        refreshOverlay()
    }

    /**
     * 通知运行中的窗口重新读取配置与形象。
     *
     * **只在 [RuntimeState.RUNNING] 时投递**：`startService` 对一个未运行的服务不会走
     * `startForegroundService` 路径，会触发后台服务启动限制（Android 8+ 直接抛
     * `IllegalStateException`）。窗口没在跑时配置已经落盘，下次启动自然会读到。
     */
    private fun refreshOverlay() {
        if (_state.value == RuntimeState.RUNNING) {
            PetOverlayService.refresh(context)
        }
    }

    // ==================== 形象资产 ====================

    /** 重新列举已安装形象。 */
    suspend fun refreshAppearances() {
        _appearances.value = installer.list()
    }

    /** 读取当前生效形象（未选/不存在时返回 null）。 */
    suspend fun effectiveAppearance(): PetAppearance? {
        val id = configRepository.get().appearanceId ?: return null
        return installer.find(id)
    }

    /**
     * 导入 `.petpack`。
     *
     * **CancellationException 必须重抛**（BR-error-handling-007）：被包裹的
     * `withContext(Dispatchers.IO)` 是真会挂起的 suspend 调用，
     * `runCatching` 会把协程取消当成业务失败吞掉，既污染错误文案又破坏取消语义。
     *
     * @return 成功返回导入的形象；失败返回可读错误（**不抛异常**，便于直接展示在设置页）
     */
    suspend fun importPack(uri: Uri): Result<PetAppearance> = try {
        val appearance = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                installer.installPack(stream)
            } ?: throw PetPackException("无法读取所选文件")
        }
        afterInstall(appearance)
        Result.success(appearance)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "petpack import failed: ${e::class.simpleName}")
        Result.failure(e)
    }

    /** 导入单张图片作为形象（取消语义同 [importPack]）。 */
    suspend fun importImage(uri: Uri): Result<PetAppearance> = try {
        val displayName = queryDisplayName(uri) ?: "pet"
        val extension = displayName.substringAfterLast('.', "png").lowercase()
        val baseName = displayName.substringBeforeLast('.', displayName).ifBlank { "pet" }
        val appearance = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                installer.installImage(stream, baseName, extension)
            } ?: throw PetPackException("无法读取所选图片")
        }
        afterInstall(appearance)
        Result.success(appearance)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "pet image import failed: ${e::class.simpleName}")
        Result.failure(e)
    }

    /** 删除形象；若删的是当前选中项则同步清除选中态（PRD US-2402 AC-6）。 */
    suspend fun removeAppearance(id: String) {
        installer.remove(id)
        if (configRepository.get().appearanceId == id) {
            configRepository.setAppearanceId(null)
            PetActionBus.emit(PetActionBus.Event.AppearanceChanged(null))
        }
        refreshAppearances()
    }

    /** 选中形象（热切换：通知运行中的窗口重建渲染）。 */
    suspend fun selectAppearance(id: String?) {
        configRepository.setAppearanceId(id)
        PetActionBus.emit(PetActionBus.Event.AppearanceChanged(id))
    }

    private suspend fun afterInstall(appearance: PetAppearance) {
        refreshAppearances()
        // 首个形象自动选中：否则用户导入完还得再点一次，属于无谓的额外步骤
        if (configRepository.get().appearanceId == null) {
            configRepository.setAppearanceId(appearance.id)
            PetActionBus.emit(PetActionBus.Event.AppearanceChanged(appearance.id))
        }
    }

    /** 查询 SAF 文件名（仅用于展示名与扩展名，**不落日志**）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    // ==================== 渲染描述符 ====================

    /**
     * 编译渲染器描述符（纯数据 → JSON 字符串）。
     *
     * 实现见 [PetDescriptorCodec]（抽成独立 object 以便**纯 JVM 单测**：
     * 本方法原先挂在持有 `Context` 的 [PetManager] 上，导致"别名/回退/URL 编码是否正确"
     * 这类最容易出错的映射逻辑无法被测试覆盖——guardrail M-6 指出了这个盲区）。
     */
    fun buildDescriptor(appearance: PetAppearance?, config: PetConfig): String =
        PetDescriptorCodec.build(appearance, config)

    /** 仅用于测试与设置页展示：形象目录的绝对路径**不对外暴露到日志**。 */
    fun assetsRoot(): File = petsDir

    companion object {
        private const val TAG = "PetManager"

        /** 形象根目录名（`filesDir/pets`）。 */
        const val PETS_DIR = "pets"

        /** 气泡文案上限（PRD US-2408 AC-1：超出以省略号收尾）。 */
        const val MAX_BUBBLE_CHARS = 80

        /**
         * 按文本长度估算 TTS 播报时长（毫秒）。
         *
         * **为什么是估算**：项目现有 TTS 引擎未提供逐字进度回调（考古取证：
         * `SystemTtsEngine` 未覆写 `onRangeStart`），拿不到真实音频时长。
         * 按中文约 5 字/秒 + 每段固定起播开销估算，足以驱动观感自然的口型。
         */
        fun estimateSpeechMs(text: String): Long {
            val chars = text.count { !it.isWhitespace() }
            return (SPEECH_BASE_MS + chars * SPEECH_PER_CHAR_MS).coerceIn(600L, 30_000L)
        }

        private const val SPEECH_BASE_MS = 400L
        private const val SPEECH_PER_CHAR_MS = 200L
    }
}
