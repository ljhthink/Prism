package io.prism.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * 基于 Android Keystore + Tink AEAD 的加密服务实现。
 *
 * **架构**：
 * 1. 在 Android Keystore 中生成 AES-256-GCM 主密钥（StrongBox 可用时自动启用，否则回退 TEE）
 * 2. 通过 [AndroidKeystoreKmsClient] 获取 Tink AEAD 原语 —— 密钥永不离开硬件
 * 3. 所有加密/解密操作由 Tink AEAD 委托给 Android Keystore
 *
 * **安全保证**：
 * - 主密钥由硬件（TEE/StrongBox）保护，Root 也无法提取
 * - AES-256-GCM 提供认证加密（confidentiality + integrity）
 * - Tink 处理 IV 随机化与密文格式（outputPrefix + nonce + ciphertext + tag）
 *
 * **API 兼容性**：
 * - minSdk 26（Android 8.0）：标准 Keystore（TEE），无 StrongBox
 * - API 28+（Android 9.0）：StrongBox 可用时自动启用，失败回退 TEE
 * - StrongBox 相关 API 通过 [RequiresApi] 注解隔离，满足 lint NewApi 检查
 *
 * US-003 验收标准 1：Android Keystore 生成主密钥（AES-256-GCM，StrongBox 可用时启用）
 * US-003 验收标准 2：DataStore + Tink AEAD 加密 API Key（本类提供 Tink AEAD 部分）
 *
 * @param context Android Context（用于检查 StrongBox 可用性）
 * @param keyAlias Keystore 中的密钥别名
 */
class KeystoreCryptoService(
    private val context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : CryptoService {

    init {
        // 注册 Tink AEAD 配置（幂等操作，多次调用安全）
        AeadConfig.register()
    }

    private val aead: Aead by lazy {
        ensureMasterKeyExists()
        AndroidKeystoreKmsClient().getAead("$KEY_URI_SCHEME$keyAlias")
    }

    /**
     * 确保 Android Keystore 中存在主密钥。
     *
     * 密钥生成策略：
     * - API 28+ 且 StrongBox 可用：尝试 StrongBox，失败回退 TEE
     * - 其他情况（API 26-27 或无 StrongBox）：标准 TEE Keystore
     */
    private fun ensureMasterKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) return

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        ) {
            tryGenerateWithStrongBox(keyGenerator)
        } else {
            generateWithTee(keyGenerator)
        }
    }

    /**
     * 使用 StrongBox 生成主密钥（API 28+）。
     *
     * 若 StrongBox 报告可用但实际生成失败（部分厂商实现缺陷），
     * 回退到 TEE（使用全新 spec，避免 builder 残留 StrongBox 标记）。
     *
     * 异常捕获策略（BR-security-002）：
     * - [StrongBoxUnavailableException]：标准 StrongBox 不可用异常
     * - [Exception]：厂商 StrongBox 实现碎片化可能抛出其他异常（ProviderException 等），
     *   统一回退 TEE 保证可用性
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun tryGenerateWithStrongBox(keyGenerator: KeyGenerator) {
        try {
            keyGenerator.init(buildKeyGenSpec().setIsStrongBoxBacked(true).build())
            keyGenerator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            // StrongBox 标准不可用异常，回退 TEE
            generateWithTee(keyGenerator)
        } catch (e: Exception) {
            // 厂商 StrongBox 实现碎片化（如 ProviderException），回退 TEE 保证可用性
            generateWithTee(keyGenerator)
        }
    }

    /**
     * 使用标准 TEE Keystore 生成主密钥（适用于所有 API 级别）。
     */
    private fun generateWithTee(keyGenerator: KeyGenerator) {
        keyGenerator.init(buildKeyGenSpec().build())
        keyGenerator.generateKey()
    }

    /**
     * 构建密钥生成参数（AES-256 / GCM / NoPadding / 仅加解密用途）。
     */
    private fun buildKeyGenSpec(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        aead.encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        aead.decrypt(ciphertext, associatedData)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_URI_SCHEME = "android-keystore://"

        /** 默认主密钥别名（版本化以便未来密钥轮换） */
        const val DEFAULT_KEY_ALIAS = "prism_master_key_v1"
    }
}
