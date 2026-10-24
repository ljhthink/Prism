package io.prism.phonecontrol.bg

// 隐藏虚拟屏后端设计参考/派生自 opencyvis/opencyvis-phone (Apache-2.0)：
// 特权虚拟屏创建与 flag 逐级降级范式（见根目录 NOTICE 与 ADR-065）。未引入其产品壳。

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

/**
 * ADR-065（US-3114）：手机操控**执行面后端**抽象。
 *
 * 两个实现：
 * - [RemoteShellBackend]（默认）：Shizuku shell 进程内自建**隐藏虚拟屏**（PUBLIC + TRUSTED
 *   flag 阶梯），主屏零窗口；截图由创建者自持 ImageReader、经 Binder 回传 PNG。
 * - [OverlayLegacyBackend]（兜底）：既有 `overlay_display_devices` 系统模拟副屏 / 自建虚拟屏，
 *   主屏可能出现可见小窗（兼容形态）。
 *
 * 失败一律**逐级降级**：隐藏后端创建失败 → 回落 legacy，并由 [VirtualDisplayManager]
 * 在 UI/工具文案中如实说明当前形态（不静默切换）。
 */
interface DisplayBackend {

    /** 后端种类（诊断与 UI 形态说明用）。 */
    enum class Kind { HIDDEN, LEGACY }

    val kind: Kind

    /** 主屏是否可能出现可见窗口（true=兼容形态，需向用户明示）。 */
    val visibleOnMainScreen: Boolean

    /** 幂等创建执行面；返回 displayId，失败返回 `Display.INVALID_DISPLAY`。 */
    fun ensure(context: Context): Int

    /** 释放执行面（幂等）。 */
    fun release(context: Context): Boolean

    /** 取最新帧（调用方负责 recycle）；无帧返回 null。maxWidth>0 时允许后端降采样预览。 */
    fun latestFrame(maxWidth: Int = 0): Bitmap?

    /** 执行面尺寸（宽, 高）；未创建返回 null。 */
    fun frameSize(): Pair<Int, Int>?

    /** 在已创建的显示面上启动目标组件（组件由调用方代码拼装）。 */
    fun launch(component: String, packageName: String, userId: Int?, forceStop: Boolean): Boolean

    /** 最近一次失败的可读原因（成功时 null）。 */
    fun lastError(): String?
}

/**
 * 隐藏虚拟屏后端（ADR-065 默认）——复刻 PoC 配方：
 * shell uid 进程 + `com.android.shell` 包上下文 + 自持 ImageReader + OpenCyvis flag 阶梯。
 *
 * 所有实际创建/截图逻辑在 [ShizukuUserService]（shell 进程）内执行，本类仅做客户端状态与
 * 结果翻译（保证失败原因可读，供上层回落与 UI 说明）。
 */
class RemoteShellBackend : DisplayBackend {

    override val kind: DisplayBackend.Kind = DisplayBackend.Kind.HIDDEN

    override val visibleOnMainScreen: Boolean = false

    @Volatile
    private var displayId: Int = android.view.Display.INVALID_DISPLAY

    @Volatile
    private var lastErrorText: String? = null

    override fun ensure(context: Context): Int {
        displayId.takeIf { it != android.view.Display.INVALID_DISPLAY }?.let { return it }
        if (!ShizukuShell.isReady()) {
            lastErrorText = "Shizuku 未就绪"
            return android.view.Display.INVALID_DISPLAY
        }
        return when (val result = ShizukuShell.createHiddenDisplay(WIDTH, HEIGHT, DENSITY)) {
            is ShizukuShell.HiddenDisplayResult.Ok -> {
                displayId = result.displayId
                lastErrorText = null
                Log.i(TAG, "隐藏虚拟屏就绪 displayId=${result.displayId} flags=0x${Integer.toHexString(result.flags)}")
                result.displayId
            }
            is ShizukuShell.HiddenDisplayResult.Error -> {
                lastErrorText = result.message
                Log.w(TAG, "隐藏虚拟屏创建失败：${result.message.take(200)}")
                android.view.Display.INVALID_DISPLAY
            }
        }
    }

    override fun release(context: Context): Boolean {
        if (displayId == android.view.Display.INVALID_DISPLAY) return true
        val released = ShizukuShell.releaseHiddenDisplay()
        displayId = android.view.Display.INVALID_DISPLAY
        if (!released) lastErrorText = "releaseHiddenDisplay 失败"
        return released
    }

    override fun latestFrame(maxWidth: Int): Bitmap? {
        val png = ShizukuShell.captureHiddenFramePng(maxWidth) ?: return null
        return runCatching { BitmapFactory.decodeByteArray(png, 0, png.size) }.getOrNull()
    }

    override fun frameSize(): Pair<Int, Int>? =
        if (displayId == android.view.Display.INVALID_DISPLAY) null else WIDTH to HEIGHT

    override fun launch(component: String, packageName: String, userId: Int?, forceStop: Boolean): Boolean =
        if (userId == null) {
            ShizukuShell.amStartOnDisplay(component, displayId)
        } else {
            ShizukuShell.amStartForUserOnDisplay(component, userId, displayId, forceStop)
        }

    override fun lastError(): String? = lastErrorText

    companion object {
        private const val TAG = "RemoteShellBackend"

        /** 执行面规格（与 legacy overlay 一致，PoC 同尺寸：720x1560@280）。 */
        const val WIDTH = 720
        const val HEIGHT = 1560
        const val DENSITY = 280
    }
}

/**
 * 兼容后端（ADR-065 兜底）：委托 [VirtualDisplayManager] 的既有路线
 * （系统模拟副屏 overlay → 自建虚拟屏），保留主屏可见小窗形态与既有全部行为。
 */
internal object OverlayLegacyBackend : DisplayBackend {

    override val kind: DisplayBackend.Kind = DisplayBackend.Kind.LEGACY

    override val visibleOnMainScreen: Boolean = true

    override fun ensure(context: Context): Int = VirtualDisplayManager.legacyEnsureDisplay(context)

    override fun release(context: Context): Boolean = VirtualDisplayManager.legacyReleaseDisplay(context)

    /** legacy 帧缓存已是位图，忽略 maxWidth（Watch 在本地自建屏路径下取全尺寸帧）。 */
    override fun latestFrame(maxWidth: Int): Bitmap? = VirtualDisplayManager.legacyLatestFrame()

    override fun frameSize(): Pair<Int, Int>? = VirtualDisplayManager.legacyFrameSize()

    override fun launch(component: String, packageName: String, userId: Int?, forceStop: Boolean): Boolean =
        VirtualDisplayManager.legacyLaunchOnDisplay(component, userId, forceStop)

    override fun lastError(): String? = null
}

/**
 * 隐藏虚拟屏名称（创建者进程与主进程启动对账共用；`dumpsys display` 可见，
 * 用于识别进程重启后的残留显示）。
 */
internal const val HIDDEN_DISPLAY_NAME = "prism-hidden-vd"

/**
 * 隐藏虚拟屏 flags（PoC / OpenCyvis 组合）。
 *
 * `TRUSTED` 等为 @SystemApi，公开 SDK 无符号——以原始位定义，供 shell 进程建屏与 JVM 单测
 * 共用同一来源（避免测试与实现漂移）。
 */
internal object HiddenDisplayFlags {

    const val PUBLIC = 1 shl 0
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** OpenCyvis 最小基座：PUBLIC｜SUPPORTS_TOUCH｜ROTATES_WITH_CONTENT｜SHOW_SYSTEM_DECORATIONS。 */
    const val BASE = PUBLIC or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or SHOW_SYSTEM_DECORATIONS

    /** PoC 验证通过的完整组合（0x1FEC1）。 */
    const val FULL = BASE or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or
        TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED

    /** 逐级降级：完整 → 去 TRUSTED → 最小 + TRUSTED → 最小。 */
    val LADDER = intArrayOf(FULL, FULL and TRUSTED.inv(), BASE or TRUSTED, BASE)
}

