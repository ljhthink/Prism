package io.prism.voice.local

/**
 * LVC-1（ADR-053）：ZipVoice 生成回调。
 *
 * ## 为什么必须是「具名类」而不是 lambda（硬约束，真机实证）
 *
 * sherpa-onnx v1.13.7 的 JNI（`sherpa-onnx/jni/offline-tts.cc::CallCallback`）按**回调对象的运行时类**
 * 硬编码查找方法：
 *
 * ```cpp
 * jclass cls = env->GetObjectClass(callback);
 * jmethodID invoke_mid = env->GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;");
 * ```
 *
 * Kotlin 的 lambda 经 `invokedynamic`（D8 再脱糖为 `<外部类>$$ExternalSyntheticLambdaN`）只暴露
 * **擦除签名** `invoke(Ljava/lang/Object;)Ljava/lang/Object;`，没有上面那个描述符 → `GetMethodID`
 * 返回空并抛 `NoSuchMethodError`；JNI 带着挂起异常继续执行 `NewFloatArray` → `JNI DETECTED ERROR`
 * → `SIGABRT`（**不可 catch，直接崩进程**）。真机证据（2026-09-20）：
 *
 * ```
 * Abort message: 'JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
 *   java.lang.NoSuchMethodError: no non-static method
 *   "Lio/prism/voice/local/SherpaZipVoiceSynthesizer$$ExternalSyntheticLambda0;.invoke([F)Ljava/lang/Integer;"'
 * ```
 *
 * kotlinc 对一个**实现 `Function1<FloatArray, Int>` 的类**会生成实体方法
 * `invoke([F)Ljava/lang/Integer;`（泛型返回类型不做原语特化 ⇒ 装箱；参数会特化）——正是 JNI 要求的形式。
 * 同类独立案例：margelo/nitro#1591 "look up Kotlin callbacks with a boxed return type"。
 *
 * **本类同时是唯一回调构造入口**：不要在 [io.prism.voice.local.SherpaZipVoiceSynthesizer] 里就地写 lambda。
 * 另见 [hasJniCompatibleInvoke]——合成的预检用它把"JNI 查不到方法"从**崩溃**降级为**可回退的失败**。
 *
 * @param isCancelled 取消探针（返回 true ⇒ 回调返回 [STOP_GENERATION]，sherpa 立即中止生成）
 * @param onChunk 分段心跳：每产出一个分段回调一次（供看门狗判定"仍在推进"，防长文本被误杀）。
 *   ⚠️ 语义提醒：返回 [STOP_GENERATION] 会让 sherpa **丢弃后续分段**，产出残音频——所以
 *   `isCancelled` 的置位必须只在"确实无进展"或"用户停止/被取代"时发生（TKN-LVC-011）。
 */
class LocalCloneSampleCallback(
    private val isCancelled: () -> Boolean,
    private val onChunk: (FloatArray) -> Unit = {}
) : (FloatArray) -> Int {

    override fun invoke(p1: FloatArray): Int {
        onChunk(p1)
        return if (isCancelled()) STOP_GENERATION else CONTINUE_GENERATION
    }

    companion object {
        /** 继续生成（sherpa `CallCallback` 返回非 0 即继续）。 */
        const val CONTINUE_GENERATION = 1

        /** 中止生成（返回 0）。 */
        const val STOP_GENERATION = 0

        /** sherpa JNI 硬编码查找的方法描述符（见类 KDoc；改动前须同步上游 JNI）。 */
        const val JNI_REQUIRED_DESCRIPTOR = "([F)Ljava/lang/Integer;"

        /**
         * 预检：`obj` 的运行时类是否具备 sherpa JNI 要求的 `invoke([F)Ljava/lang/Integer;`。
         *
         * 用**与 JNI 同构的查找方式**（`getMethod(name, FloatArray::class.java)` + 返回类型必须是装箱 `Integer`），
         * 使"签名不符"在调用 native 之前就被发现——否则后果是 SIGABRT 崩进程（不可捕获）。
         *
         * @return true 表示可安全传给 `generateWithConfigAndCallback`
         */
        fun hasJniCompatibleInvoke(obj: Any): Boolean = try {
            obj.javaClass.getMethod("invoke", FloatArray::class.java).returnType == java.lang.Integer::class.java
        } catch (e: NoSuchMethodException) {
            false
        }
    }
}
