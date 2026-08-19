package io.prism.vision

import io.prism.config.VisionBypassConfigRepository
import io.prism.data.ProviderConfig
import kotlinx.coroutines.CancellationException

/**
 * 视觉旁路结果（v1 US-301/302）。
 */
sealed class VisionBypassResult {
    /** 云端视觉 Provider 生成描述（成功主路径）。 */
    data class Cloud(val description: String) : VisionBypassResult()

    /** OCR 兜底提取文字（云端失败后本地兜底）。 */
    data class Ocr(val text: String) : VisionBypassResult()

    /** 旁路不可用（未授权 / 关闭 / 熔断 / 云端+OCR 均失败 / 无视觉配置）。 */
    data object Unavailable : VisionBypassResult()
}

/**
 * v1 US-301/302：视觉旁路编排器（纯逻辑可测）——「云端视觉旁路 → OCR 兜底 → 不可用」降级链。
 *
 * **职责**：当主聊天 Provider 为纯文本模型（图片直传被拒 + 视觉不支持信号）时，编排：
 * 1. **云端旁路**：[cloudDescriber] 调用视觉 Provider（OpenAI 兼容 image_url → text）生成图片描述
 * 2. **熔断**：云端失败 → [VisionBypassConfigRepository.recordFailure]（连续 [MAX_FAILURES] 次
 *    自动停用自动旁路，防限流放大/反复失败）；成功 → resetFailures
 * 3. **OCR 兜底**：云端失败后 [ocrExtractor] 本地提取文字（非空才注入）
 * 4. 两者均失败/不可用 → [VisionBypassResult.Unavailable]（保留原错误提示）
 *
 * **可测性**：cloudDescriber / ocrExtractor 以函数依赖注入，纯逻辑可在 JVM 单测验证
 * 降级链与熔断；真实实现由 [io.prism.PrismApplication] 注入（复用 OpenAICompatibleProvider
 * 的 chatCompletion + ML Kit OCR）。
 *
 * @param config 旁路配置仓库（授权/开关/熔断）
 * @param cloudDescriber 云端视觉描述函数：入参 (图片 dataUrl, 用户文本, 视觉 Provider 配置) → 描述文本
 * @param ocrExtractor OCR 提取函数（suspend）：入参图片 dataUrl → 提取文字（可空 = 无 OCR / 无文字）
 */
class VisionBypassOrchestrator(
    private val config: VisionBypassConfigRepository,
    private val cloudDescriber: suspend (imageDataUrl: String, userText: String, visionConfig: ProviderConfig) -> String?,
    private val ocrExtractor: suspend (imageDataUrl: String) -> String? = { null }
) {

    /**
     * 解析图片为文字描述/文字（降级链主入口）。
     *
     * **降级链**（guardrail M-4 重构）：
     * 1. **云端旁路**：仅当「已授权 + 自动开关 + 未熔断 + 有视觉配置」时尝试（[VisionBypassConfigRepository.isBypassAvailable]）；
     *    成功 → Cloud（清零熔断）；失败 → 计熔断
     * 2. **OCR 兜底**：云端不可用/失败后**始终**尝试（本地离线，无需授权/熔断不影响）；
     *    非空 → Ocr
     * 3. 两者均失败/无结果 → Unavailable
     *
     * @param imageDataUrl 图片 data URL（`data:image/...;base64,...`）
     * @param userText 用户提问文本（作为视觉描述上下文）
     * @param visionConfig 视觉旁路 Provider 配置（null 时跳过云端，仅 OCR 兜底）
     * @return [VisionBypassResult]：Cloud / Ocr / Unavailable
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun resolve(
        imageDataUrl: String,
        userText: String,
        visionConfig: ProviderConfig?,
        isDedicated: Boolean = false
    ): VisionBypassResult {
        // 1. 云端视觉旁路。guardrail 复审（TKN-SEARCH-VISION-ROUND5-001，B-1/A-No.1）修订：
        //    专用 Provider 只**跳过熔断**（打标后连续失败不再把 cloud 锁死，避免"激活了却永远只 OCR"），
        //    但**必须仍尊重两项硬信号——`autoBypass` 开关 + 用户显式撤销授权（consent=false）**。
        //    consent 的授予链路：用户把某 Provider 标记为 isVisionFallback 时
        //    [io.prism.ui.settings.SettingsViewModel.saveProvider] 会自动 `setConsent(true)`，
        //    因此正常"激活视觉模型"路径 consent 恒为 true；此处仍校验 consent，正是为了拦截
        //    "用户到设置页关闭图片外发授权后，专用 Provider 仍把图片外发"的隐私回归（ADR-035 隐私铁门）。
        //    非专用（回退到主 Provider）场景保持严格 consent && auto && failures<3，防纯文本主模型反复打空枪。
        val auto = config.isAutoBypassEnabled()
        val cloudAllowed = if (isDedicated) {
            auto && config.isConsentGiven()
        } else {
            visionConfig != null && auto && config.isConsentGiven() && config.getConsecutiveFailures() < io.prism.config.VisionBypassConfigRepository.MAX_FAILURES
        }
        val cloudAvailable = visionConfig != null && cloudAllowed
        if (cloudAvailable) {
            val description = try {
                cloudDescriber(imageDataUrl, userText, visionConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (!description.isNullOrBlank()) {
                config.resetFailures()
                return VisionBypassResult.Cloud(description)
            }
            // 云端失败 → 熔断计数（OCR 成功也计云失败，防反复试探云端限流）
            config.recordFailure()
        }

        // 2. OCR 兜底（本地离线，无需授权；云端不可用/失败均可达）
        val text = try {
            ocrExtractor(imageDataUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (!text.isNullOrBlank()) return VisionBypassResult.Ocr(text)

        return VisionBypassResult.Unavailable
    }

    companion object {
        /** 云端视觉描述注入 user 消息的前缀（改写最后一条 user 消息用）。 */
        const val IMAGE_DESC_PREFIX = "【图片内容】"

        /** OCR 文字注入 user 消息的前缀（改写最后一条 user 消息用）。 */
        const val IMAGE_OCR_PREFIX = "【图片文字】"

        /**
         * 视觉模型系统提示（纯函数可测）：要求逐字转录图中文字 + 描述场景/物体/布局/颜色。
         */
        fun buildVisionSystemPrompt(): String = VISION_SYSTEM_PROMPT

        /**
         * 视觉模型用户提示（纯函数可测）：拼接用户原始提问作为描述上下文。
         */
        fun buildVisionUserPrompt(userText: String): String =
            if (userText.isBlank()) "请描述这张图片的内容。" else "用户发来一张图片并提问：$userText。请详细描述图片内容以帮助回答。"

        private val VISION_SYSTEM_PROMPT = """
你是图片描述助手。请详细描述用户发送的图片，帮助无法直接看图的文本模型理解：
1. 逐字转录图中所有可见文字（包括标题、正文、按钮、票据数字等）
2. 描述图片中的物体、场景、空间布局、颜色
3. 区分场景类型（截图/文档/票据/照片/图表等）
4. 用简体中文回答，结构化、条理清晰
不要评价图片内容，只做客观描述。
        """.trimIndent()
    }
}
