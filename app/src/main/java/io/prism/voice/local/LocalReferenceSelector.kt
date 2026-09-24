package io.prism.voice.local

/**
 * LVC-1（ADR-053 决策 5）：参考音频选择（纯函数，无 IO，可 JVM 单测）。
 *
 * **v1 策略**：按"目标文本与参考文本的**模型可见字数**差最小"确定性选取——差值越小，
 * 参考音频与待合成文本的语速/韵律域越接近，ZipVoice 的克隆稳定性越好。平局取清单序首个
 * （确定性，不依赖字典序或哈希）。
 *
 * **为什么不做情绪分类**：中文情绪分类需要额外模型依赖，且对短句分类准确率不足——
 * 引入即"用不确定换不确定"。清单里的 `emotion` 仅作展示与人工调参线索，**不参与自动选择**。
 *
 * **为什么不用"最长参考"或"固定第一条"**：固定第一条在文本长度差异大时（如 3 字短句配
 * 8.6s 参考）会明显影响韵律；最长参考则拖慢推理（官方：prompt 越长推理越慢且音质退化）。
 */
object LocalReferenceSelector {

    /**
     * 选择参考音频。
     *
     * @param normalizedText **已经过 [LocalCloneTextNormalizer] 规范化**的目标文本
     *   （未规范化的文本会引入长度偏差；调用方约定）
     * @param references 参考集（空列表 → null）
     * @return 选中的参考；参考集为空或全部参考文本规范化后为空 → null（调用方据此判失败）
     */
    fun select(normalizedText: String, references: List<LocalReference>): LocalReference? {
        if (references.isEmpty()) return null
        val targetLen = normalizedText.length
        var best: LocalReference? = null
        var bestDiff = Int.MAX_VALUE
        for (ref in references) {
            val refLen = LocalCloneTextNormalizer.normalize(ref.text).length
            if (refLen == 0) continue // 无有效文本的参考不可用（模型侧会产出异常音频）
            val diff = kotlin.math.abs(refLen - targetLen)
            // 严格小于：平局保持先出现者（清单序），保证确定性
            if (diff < bestDiff) {
                bestDiff = diff
                best = ref
            }
        }
        return best
    }
}
