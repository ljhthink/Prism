package io.prism.security

/**
 * 加密服务接口 —— 提供 AEAD（认证加密）能力。
 *
 * 设计为接口以支持依赖反转与测试隔离：
 * - 生产环境：[KeystoreCryptoService] 使用 Android Keystore + Tink AEAD（硬件级安全）
 * - 测试环境：纯 Tink AEAD 实现（无需 Android 设备，见 test 源码集）
 *
 * US-003 验收标准 1-2 的契约层。
 */
interface CryptoService {

    /**
     * 加密明文数据。
     *
     * @param plaintext 待加密的明文字节
     * @param associatedData 关联数据（AAD），用于认证但不加密。可为 null。
     * @return 密文字节（含 IV/nonce 和认证标签）
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray

    /**
     * 解密密文数据。
     *
     * @param ciphertext 待解密的密文字节
     * @param associatedData 关联数据（AAD），必须与加密时一致。可为 null。
     * @return 明文字节
     * @throws GeneralSecurityException 如果解密失败（密文损坏/篡改/AAD 不匹配）
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray? = null): ByteArray
}
