package io.prism.ui.settings

import io.objectbox.BoxStore
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.security.ApiKeyRepository
import io.prism.security.FakePreferenceDataStore
import io.prism.security.RecordingCryptoService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v1 US-201：SettingsViewModel 手机操控（无障碍服务）连接状态测试 —— 验证
 * [SettingsViewModel.refreshPhoneControlStatus] 状态更新逻辑（设置页 UI 轮询驱动）。
 *
 * **设计**：不内置无限轮询循环（避免 runTest/StandardTestDispatcher 下调度器永不空闲挂起），
 * 由设置页 Composable LaunchedEffect 定时调用本方法刷新；本测试验证方法逻辑正确。
 */
class SettingsViewModelPhoneControlStatusTest {

    private lateinit var boxStore: BoxStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory(prefix = "settings-phone-status-").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    private fun createVm(provider: () -> Boolean): SettingsViewModel =
        SettingsViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService()),
            phoneControlStatusProvider = provider
        )

    @Test
    fun `initial state reflects provider`() {
        val vm = createVm { true }
        assertTrue("服务已连接时初始应为 true", vm.phoneControlConnected.value)

        val vm2 = createVm { false }
        assertFalse("服务未连接时初始应为 false", vm2.phoneControlConnected.value)
    }

    @Test
    fun `refresh updates state when provider flips to connected`() {
        var connected = false
        val vm = createVm { connected }
        assertFalse(vm.phoneControlConnected.value)

        connected = true
        vm.refreshPhoneControlStatus()
        assertTrue("refresh 后应反映已连接", vm.phoneControlConnected.value)
    }

    @Test
    fun `refresh updates state when provider flips to disconnected`() {
        var connected = true
        val vm = createVm { connected }
        assertTrue(vm.phoneControlConnected.value)

        connected = false
        vm.refreshPhoneControlStatus()
        assertFalse("refresh 后应反映未连接", vm.phoneControlConnected.value)
    }

    @Test
    fun `null provider degrades to false`() {
        val vm = SettingsViewModel(
            providerRepository = ProviderConfigRepository(boxStore),
            apiKeyRepository = ApiKeyRepository(FakePreferenceDataStore(), RecordingCryptoService()),
            phoneControlStatusProvider = null
        )
        assertFalse("无 provider 时降级 false", vm.phoneControlConnected.value)
        vm.refreshPhoneControlStatus()
        assertFalse("无 provider 时 refresh 后仍 false", vm.phoneControlConnected.value)
    }
}
