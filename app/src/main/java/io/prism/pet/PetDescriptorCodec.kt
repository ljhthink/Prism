package io.prism.pet

import io.prism.pet.asset.PetPackParser
import io.prism.pet.model.PetActionCatalog
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetAppearance
import io.prism.pet.model.PetAppearanceKind
import io.prism.pet.model.PetConfig
import io.prism.pet.overlay.PetAssetRoutes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 批次24（ADR-051 D2/D3）：**渲染描述符编译器** —— 纯函数，可 JVM 单测。
 *
 * 把 [PetAppearance] + [PetConfig] 编译成 WebView 渲染器可吃的 JSON。
 * 从 [PetManager] 抽出为独立 object 的原因（guardrail M-6）：该方法**不依赖 Context**，
 * 却是整条链上最容易出错的一环（别名映射 / 负下标哨兵 / 路径 URL 编码 / 配置钳制），
 * 挂在持有 `Context` 的门面上会让它永远拿不到测试覆盖。
 *
 * **路径必须 URL 化**：形象资产在磁盘上的相对路径可能含 `#`、空格、CJK
 * （昔涟的 motion 文件名就是 `动作#6_0.motion3.json`），直接拼进 URL 会被当作
 * fragment 分隔符截断。编码规则与 [PetPackParser.encodePathForUrl] 完全一致。
 */
object PetDescriptorCodec {

    /**
     * @return 渲染器描述符 JSON；`appearance == null` 时返回"无形象"的最小描述符
     *   （渲染器据此显示占位提示，而不是加载失败）
     */
    fun build(appearance: PetAppearance?, config: PetConfig): String {
        val clamped = config.clamped()
        val obj: JsonObject = buildJsonObject {
            if (appearance == null) {
                put("kind", "image")
                put("imageUrls", buildJsonArray { })
            } else {
                put("kind", if (appearance.kind == PetAppearanceKind.LIVE2D) "live2d" else "image")
                appearance.model?.let { put("modelUrl", petUrl(appearance.id, it)) }
                put("imageUrls", buildJsonArray {
                    appearance.images.forEach { add(kotlinx.serialization.json.JsonPrimitive(petUrl(appearance.id, it))) }
                })
                put(
                    "frameDurationMs",
                    appearance.frameDurationMs.takeIf { it > 0 } ?: PetAppearance.DEFAULT_FRAME_MS
                )
            }
            // 最终缩放 = 形象自带缩放 × 用户设置缩放（两者语义不同：前者补偿模型 canvas 差异，
            // 后者是用户偏好，必须相乘而不是二选一）
            put("scale", (appearance?.scale ?: 1.0f).toDouble() * clamped.scale.toDouble())
            put("reduceMotion", clamped.reduceMotion)
            put("lowPower", clamped.lowPowerMode)
            put("expressionResetSeconds", clamped.expressionResetSeconds)
            appearance?.defaultExpression?.let { put("defaultExpression", it) }
            appearance?.tapAlias?.let { put("tapAlias", it) }
            put("idleAliases", buildJsonArray {
                PetActionCatalog.idleAliases(appearance).forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            })
            put("actions", buildJsonObject {
                appearance?.actions?.forEach { (alias, ref) ->
                    if (ref.target.isBlank()) return@forEach
                    put(alias, buildJsonObject {
                        put("kind", if (ref.kind == PetActionKind.MOTION) "motion" else "expression")
                        put("target", ref.target)
                        // 负下标必须原样下发（渲染层据此做"组内随机"），不得在此钳制
                        put("index", ref.index)
                        ref.displayName?.let { put("name", it) }
                        ref.fallbackExpression?.let { put("fallbackExpression", it) }
                    })
                }
            })
            put("hitActions", buildJsonObject {
                appearance?.hitActions?.forEach { (area, alias) -> put(area, alias) }
            })
        }
        return obj.toString()
    }

    /** 形象资产的虚拟 URL（`/pets/<百分号编码 id>/<百分号编码相对路径>`）。 */
    fun petUrl(appearanceId: String, relativePath: String): String =
        PetAssetRoutes.PETS_PREFIX +
            PetPackParser.encodePathForUrl(appearanceId) + "/" +
            PetPackParser.encodePathForUrl(relativePath)
}
