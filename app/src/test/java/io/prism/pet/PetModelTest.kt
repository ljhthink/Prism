package io.prism.pet

import io.prism.pet.model.PetActionCatalog
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetActionRef
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import io.prism.pet.model.PetConfig
import io.prism.pet.model.PetIdlePolicy
import io.prism.pet.model.PetWindowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 批次24（ADR-051）：桌宠纯模型层单测（动作目录 / 配置钳制 / 待机策略 / 窗口几何）。
 *
 * 全部为**纯函数**，不依赖 Android 与文件系统。
 */
class PetModelTest {

    private val cyrene = PetAppearance(
        id = "cyrene",
        name = "昔涟",
        kind = PetAppearanceKind.LIVE2D,
        model = "Cyrene.model3.json",
        actions = mapOf(
            "happy" to PetActionRef(PetActionKind.EXPRESSION, "开心眼"),
            "wave" to PetActionRef(PetActionKind.MOTION, "动作#6", 0, "动作回正", "表情回正"),
            "wink" to PetActionRef(PetActionKind.MOTION, "动作#6", 1, "Wink~", "表情回正"),
            "reset" to PetActionRef(PetActionKind.EXPRESSION, "表情回正")
        ),
        hitActions = mapOf("左侧秋千" to "wave", "星星眼1坠" to "happy"),
        idleAliases = listOf("wink", "happy"),
        tapAlias = "happy",
        defaultExpression = "表情回正"
    )

    // ==================== 动作目录 ====================

    @Test
    fun `别名归一化 - 大小写与空白`() {
        assertEquals("happy", PetActionCatalog.normalizeAlias("  HAPPY "))
        assertEquals("happy", PetActionCatalog.normalizeAlias("Happy"))
        assertNull(PetActionCatalog.normalizeAlias("   "))
        assertNull(PetActionCatalog.normalizeAlias(null))
    }

    @Test
    fun `解析别名 - 命中与未命中`() {
        assertEquals("开心眼", PetActionCatalog.resolve(cyrene, "HAPPY")?.target)
        assertEquals(PetActionKind.MOTION, PetActionCatalog.resolve(cyrene, "wave")?.kind)
        // 未映射的别名必须**安全忽略**（返回 null，不抛异常）
        assertNull(PetActionCatalog.resolve(cyrene, "angry"))
        assertNull(PetActionCatalog.resolve(cyrene, null))
        assertNull(PetActionCatalog.resolve(null, "happy"))
    }

    @Test
    fun `可用别名 - 仅含可解析项且排序`() {
        assertEquals(listOf("happy", "reset", "wave", "wink"), PetActionCatalog.availableAliases(cyrene))
        assertTrue(PetActionCatalog.availableAliases(null).isEmpty())
    }

    @Test
    fun `待机别名 - 过滤不可解析项`() {
        val broken = cyrene.copy(idleAliases = listOf("wink", "not-mapped", "wink", "  HAPPY "))
        assertEquals(listOf("wink", "happy"), PetActionCatalog.idleAliases(broken))
    }

    @Test
    fun `点击兜底别名 - 配置优先 缺省回落待机首位`() {
        assertEquals("happy", PetActionCatalog.tapAlias(cyrene))
        // 配置的别名不可解析 → 回落待机首位
        assertEquals("wink", PetActionCatalog.tapAlias(cyrene.copy(tapAlias = "not-mapped", idleAliases = listOf("wink"))))
        // 全空 → null
        assertNull(PetActionCatalog.tapAlias(cyrene.copy(tapAlias = null, idleAliases = emptyList())))
    }

    @Test
    fun `动作引用 - wireId 稳定且不含用户内容`() {
        assertEquals("motion:动作#6#1", PetActionRef(PetActionKind.MOTION, "动作#6", 1).wireId)
        assertEquals("expression:开心眼", PetActionRef(PetActionKind.EXPRESSION, "开心眼").wireId)
    }

    // ==================== 配置钳制 ====================

    @Test
    fun `配置钳制 - 越界值收敛到合法域`() {
        val wild = PetConfig(
            appearanceId = "  ",
            scale = 99f,
            opacity = -1f,
            posX = 5f,
            posY = -3f,
            expressionResetSeconds = 999
        ).clamped()
        assertEquals(PetConfig.MAX_SCALE, wild.scale, 0.0001f)
        assertEquals(PetConfig.MIN_OPACITY, wild.opacity, 0.0001f)
        assertEquals(1f, wild.posX, 0.0001f)
        assertEquals(0f, wild.posY, 0.0001f)
        assertEquals(PetConfig.MAX_EXPRESSION_RESET_SECONDS, wild.expressionResetSeconds)
        assertNull(wild.appearanceId)
    }

    @Test
    fun `配置钳制 - 非有限浮点回落到边界而非 NaN`() {
        val nan = PetConfig(scale = Float.NaN, opacity = Float.NaN, posX = Float.NaN, posY = Float.NaN).clamped()
        assertFalse(nan.scale.isNaN())
        assertFalse(nan.opacity.isNaN())
        assertFalse(nan.posX.isNaN())
        assertFalse(nan.posY.isNaN())
    }

    @Test
    fun `配置钳制 - 幂等`() {
        val once = PetConfig(scale = 3f, opacity = 0f, posX = 2f).clamped()
        assertEquals(once, once.clamped())
    }

    @Test
    fun `idleActive - 关闭或减少动效时待机失效`() {
        assertTrue(PetConfig(enabled = true, idleEnabled = true, reduceMotion = false).idleActive)
        assertFalse(PetConfig(enabled = false, idleEnabled = true).idleActive)
        assertFalse(PetConfig(enabled = true, idleEnabled = false).idleActive)
        assertFalse(PetConfig(enabled = true, reduceMotion = true).idleActive)
    }

    @Test
    fun `位置差异 - 抖动不触发落盘`() {
        val a = PetConfig(posX = 0.5f, posY = 0.5f)
        val tiny = a.copy(posX = 0.5f + PetConfig.POSITION_EPSILON / 2f)
        assertFalse(tiny.positionDiffersFrom(a))
        assertTrue(a.copy(posX = 0.9f).positionDiffersFrom(a))
    }

    // ==================== 待机策略 ====================

    @Test
    fun `待机延迟 - 落在配置域内`() {
        val config = PetConfig(enabled = true, idleEnabled = true, reduceMotion = false)
        repeat(200) {
            val delay = PetIdlePolicy.nextDelayMs(config, Random(it))!!
            assertTrue("delay=$delay", delay in PetIdlePolicy.MIN_IDLE_MS..PetIdlePolicy.MAX_IDLE_MS)
        }
    }

    @Test
    fun `待机延迟 - 关闭时不排程`() {
        assertNull(PetIdlePolicy.nextDelayMs(PetConfig(enabled = false)))
        assertNull(PetIdlePolicy.nextDelayMs(PetConfig(enabled = true, idleEnabled = false)))
        assertNull(PetIdlePolicy.nextDelayMs(PetConfig(enabled = true, reduceMotion = true)))
    }

    // ==================== 窗口几何 ====================

    @Test
    fun `窗口几何 - 归一化位置映射到自由空间`() {
        val leftTop = PetWindowGeometry.resolve(1000, 2000, 400, 540, 0f, 0f)
        assertEquals(0, leftTop.x)
        assertEquals(0, leftTop.y)
        val rightBottom = PetWindowGeometry.resolve(1000, 2000, 400, 540, 1f, 1f)
        assertEquals(600, rightBottom.x)
        assertEquals(1460, rightBottom.y)
        val middle = PetWindowGeometry.resolve(1000, 2000, 400, 540, 0.5f, 0.5f)
        assertEquals(300, middle.x)
        assertEquals(730, middle.y)
    }

    /** 结构性保证（PRD US-2403 AC-4）：任何越界归一化值都不能把窗口推出可用区域。 */
    @Test
    fun `窗口几何 - 越界归一化值不逃逸可用区域`() {
        listOf(-5f, 0f, 0.5f, 1f, 9f).forEach { pos ->
            val frame = PetWindowGeometry.resolve(1080, 2400, 400, 540, pos, pos)
            assertTrue("x=${frame.x}", frame.x >= 0)
            assertTrue("y=${frame.y}", frame.y >= 0)
            assertTrue("right", frame.x + frame.width <= 1080)
            assertTrue("bottom", frame.y + frame.height <= 2400)
        }
    }

    @Test
    fun `窗口几何 - 窗口大于屏幕时钳到屏幕尺寸`() {
        val frame = PetWindowGeometry.resolve(320, 480, 9999, 9999, 0.5f, 0.5f)
        assertEquals(320, frame.width)
        assertEquals(480, frame.height)
        assertTrue(frame.x >= 0 && frame.y >= 0)
    }

    @Test
    fun `窗口几何 - 可用区域非法时返回不可布局矩形`() {
        assertFalse(PetWindowGeometry.resolve(0, 0, 100, 100, 0.5f, 0.5f).isLayoutable)
        assertFalse(PetWindowGeometry.resolve(-1, 100, 100, 100, 0.5f, 0.5f).isLayoutable)
    }

    @Test
    fun `窗口几何 - normalize 与 resolve 互逆`() {
        val frame = PetWindowGeometry.resolve(1000, 2000, 400, 540, 0.25f, 0.75f)
        assertEquals(0.25f, PetWindowGeometry.normalize(1000, 400, frame.x), 0.0005f)
        assertEquals(0.75f, PetWindowGeometry.normalize(2000, 540, frame.y), 0.0005f)
    }

    @Test
    fun `窗口几何 - 无自由空间时 normalize 返回 0`() {
        assertEquals(0f, PetWindowGeometry.normalize(400, 400, 123), 0.0001f)
        assertEquals(0f, PetWindowGeometry.normalize(300, 400, 123), 0.0001f)
    }

    // ==================== 播报时长估算 ====================

    @Test
    fun `播报时长估算 - 随字数增长且有上下界`() {
        val short = PetManager.estimateSpeechMs("嗯。")
        val long = PetManager.estimateSpeechMs("这是一段比较长的回复内容，用来验证估算随时长增长。")
        assertTrue("short=$short", short >= 600L)
        assertTrue(long > short)
        assertTrue(PetManager.estimateSpeechMs("啊".repeat(1000)) <= 30_000L)
    }
}
