package io.prism.pet

import io.prism.pet.model.PetActionCatalog
import io.prism.pet.model.PetActionKind
import io.prism.pet.model.PetAppearance
import io.prism.skill.LocalToolExecutor
import io.prism.network.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 批次24（ADR-051 D8 / PRD US-2409）：`pet__play_action` —— 让角色**用自己的身体说话**。
 *
 * **上游对应物**：`Cyrene-Agent` 的 `play_live2d_action`。上游把动作别名目录
 * （`src/shared/live2d-actions.ts`）同时用于"构造工具描述"与"渲染层派发"，
 * 保证模型看到的可选值**恰好**是渲染层能执行的值。本类沿用该设计：
 * 工具定义的 `enum` 直接取 [PetActionCatalog.BUILTIN_ALIASES]。
 *
 * **降级契约**（全部返回可读字符串，绝不抛异常——对齐 [LocalToolExecutor] 契约）：
 * | 情形 | 返回 |
 * | --- | --- |
 * | 桌宠未开启 | 明确说明"当前未开启"，并提示可在设置中打开 |
 * | 无形象 / 形象未映射该别名 | 说明原因 + 列出该形象**实际可用**的别名 |
 * | 正常 | 说明"已让形象播放 X" |
 *
 * **可测性**：依赖全部以函数注入（[appearanceProvider] / [enabledProvider] / [dispatch]），
 * 不含 Android 依赖，纯 JVM 可测。
 *
 * @param appearanceProvider 读取当前生效形象（无则 null）
 * @param enabledProvider 桌宠是否开启
 * @param dispatch 派发动作（生产：向 [PetActionBus] 发 [PetActionBus.Event.PlayAction]）
 */
class PetLocalToolExecutor(
    private val appearanceProvider: suspend () -> PetAppearance?,
    private val enabledProvider: suspend () -> Boolean,
    private val dispatch: (String) -> Unit = { alias ->
        PetActionBus.emit(PetActionBus.Event.PlayAction(alias))
    }
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_PLAY_ACTION

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        if (toolName != TOOL_PLAY_ACTION) return "未知本地工具: $toolName"

        val rawAlias = (arguments["action"] as? String)?.trim().orEmpty()
        if (rawAlias.isEmpty()) return "缺少 action 参数。可用动作：${BUILTIN_ALIAS_HINT}"

        if (!enabledProvider()) {
            return "桌宠当前未开启，无法播放动作。可在「设置 → 桌宠」中打开桌宠后重试。"
        }

        val appearance = appearanceProvider()
        if (appearance == null) {
            return "尚未选择桌宠形象，无法播放动作。可在「设置 → 桌宠」中导入并选择一个形象。"
        }

        val ref = PetActionCatalog.resolve(appearance, rawAlias)
        if (ref == null) {
            val available = PetActionCatalog.availableAliases(appearance)
            return if (available.isEmpty()) {
                "当前形象「${appearance.name}」没有配置任何动作，无法播放「$rawAlias」。"
            } else {
                "当前形象「${appearance.name}」没有动作「$rawAlias」。这个形象支持的动作是：" +
                    available.joinToString("、")
            }
        }

        dispatch(PetActionCatalog.normalizeAlias(rawAlias)!!)
        val label = ref.displayName?.takeIf { it.isNotBlank() } ?: rawAlias
        val kindText = if (ref.kind == PetActionKind.MOTION) "动作" else "表情"
        return "已让「${appearance.name}」播放$kindText「$label」。"
    }

    companion object {
        /** 工具命名空间前缀（与项目既有 `namespace__tool` 约定一致）。 */
        const val NAMESPACE_PREFIX = "pet__"

        /** 工具名（`pet__play_action`）。 */
        const val TOOL_PLAY_ACTION = "${NAMESPACE_PREFIX}play_action"

        private val BUILTIN_ALIAS_HINT: String = PetActionCatalog.BUILTIN_ALIASES.joinToString("、")

        /**
         * 构建 `pet__play_action` 工具定义（供 `ConversationViewModel.buildTools` 合并）。
         *
         * **参数**：`action` 必填，取 [PetActionCatalog.BUILTIN_ALIASES] 作为 `enum`——
         * 用枚举而非自由字符串，是因为模型对"可选集合"的遵循度显著高于对描述文字的遵循度，
         * 而**别名拼错在渲染层表现为静默无反应**（本批次最容易踩的坑之一）。
         *
         * `additionalProperties = false`：与项目其它本地工具一致（拒绝未知字段）。
         */
        fun buildToolDefinition(): ToolDefinition {
            val parameters = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "action" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive(
                                        "要播放的动作别名。心情类：happy 开心 / sad 低落 / angry 生气 / " +
                                            "surprised 惊讶 / shy 害羞 / think 思考；" +
                                            "互动类：wave 打招呼 / wink 眨眼 / tap 被点的反应 / " +
                                            "idle 待机自然动作；reset 复位（表情回正）。"
                                    ),
                                    "enum" to JsonArray(
                                        PetActionCatalog.BUILTIN_ALIASES.map { JsonPrimitive(it) }
                                    )
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("action"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_PLAY_ACTION,
                    description = "让桌面上的角色形象播放一个动作或表情，使表达与你说的话一致。" +
                        "当回复带有明显情绪（开心/难过/惊讶/害羞/思考），或在打招呼、道别、" +
                        "被夸赞、被调侃等需要「用身体回应」的场合调用。" +
                        "同一轮不要连续调用多次；纯信息性回答、工具执行结果说明等场合不要调用。" +
                        "若返回「桌宠未开启」，说明用户当前没有打开桌宠，不必再试。",
                    parameters = parameters
                )
            )
        }
    }
}
