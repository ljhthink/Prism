package io.prism.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * 测试用 CryptoService 实现 —— 基于 Tink 纯 JVM AEAD（AES-256-GCM）。
 *
 * **用途**：
 * - 在 JVM 单元测试环境中提供真实加密/解密（不依赖 Android Keystore）
 * - 记录所有 encrypt/decrypt 调用，用于验证「明文不落盘」契约
 *
 * **与生产环境 [KeystoreCryptoService] 的区别**：
 * - 密钥由 Tink 在内存中生成（KeysetHandle），不使用 Android Keystore 硬件保护
 * - 加密算法相同（AES-256-GCM），加密语义一致
 *
 * US-003 验收标准 3 的测试基础设施。
 */
class RecordingCryptoService : CryptoService {

    private val aead: Aead

    /** 记录所有 encrypt 调用接收的明文字节（副本） */
    val encryptCalls: MutableList<ByteArray> = mutableListOf()

    /** 记录所有 decrypt 调用接收的密文字节（副本） */
    val decryptCalls: MutableList<ByteArray> = mutableListOf()

    init {
        AeadConfig.register()
        val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
        aead = handle.getPrimitive(Aead::class.java)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        // 记录明文副本（防止外部修改影响记录）
        encryptCalls.add(plaintext.copyOf())
        return aead.encrypt(plaintext, associatedData)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
        // 记录密文副本
        decryptCalls.add(ciphertext.copyOf())
        return aead.decrypt(ciphertext, associatedData)
    }
}
