package io.prism.crossapp

import io.prism.network.ToolDefinition
import io.prism.skill.LocalToolExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 跨 App 调用本地工具执行器（M6 Phase B，ADR-016）。
 *
 * 实现 [LocalToolExecutor] 接口，将 LLM tool_calling 请求映射到 [CrossAppLauncher] 的
 * 三个核心能力（Deep Link 跳转 / Share Sheet 分享 / 系统 Picker 选取）。
 *
 * **工具命名空间**（ADR-016 5.4）：`cross_app__` 前缀，与 Skill 的 `skillName__` 平行。
 * - [TOOL_OPEN_APP]（`cross_app__open_app`）：打开指定 App
 * - [TOOL_SHARE_CONTENT]（`cross_app__share_content`）：分享文本内容
 * - [TOOL_PICK_MEDIA]（`cross_app__pick_media`）：选取媒体/文档
 *
 * **参数映射**：
 * - LLM 传入的 JSON 参数 Map 由 [SkillExecutor] 转发，本执行器提取必需字段并调用
 *   [CrossAppLauncher] 对应方法
 * - `cross_app__open_app` 的 action 模板参数（如 `itemId`）从 arguments 中提取已知字段
 *   （appId/action）之外的键作为模板替换变量，经 [CrossAppLauncher.resolveTemplates]
 *   URL 编码后替换（BR-security-006）
 *
 * **降级策略**（与 MCP 工具一致）：所有失败场景返回描述性字符串（而非抛异常），
 * 由 SkillExecutor 作为 tool result 回灌给 LLM。
 *
 * **协程取消**（BR-error-handling-007）：本执行器不使用 runCatching，
 * CancellationException 自然传播给 SkillExecutor。
 *
 * **测试性**（BR-testing-004）：通过 [crossAppLauncher] 注入解耦，
 * 测试可注入 fake CrossAppLauncher（或 fake SchemeRegistry/Checker/Bridge）纯 JVM 验证。
 */
class CrossAppLocalToolExecutor(
    private val crossAppLauncher: CrossAppLauncher
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName in HANDLED_TOOLS

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        return when (toolName) {
            TOOL_OPEN_APP -> executeOpenApp(arguments)
            TOOL_SHARE_CONTENT -> executeShareContent(arguments)
            TOOL_PICK_MEDIA -> executePickMedia(arguments)
            else -> "未知跨 App 工具: $toolName"
        }
    }

    /**
     * 执行 `cross_app__open_app`：Deep Link 跳转打开目标 App。
     *
     * **参数提取**：
     * - `appId`（必需）：目标 App 标识符
     * - `action`（可选）：功能名（如 `scan` / `item` / `route`）
     * - 其余字段作为模板替换参数（如 `itemId` / `awemeId` / `origin` / `dest`）
     */
    private suspend fun executeOpenApp(args: Map<String, Any?>): String {
        val appId = args["appId"]?.toString()
            ?: return "缺少必需参数 appId"
        val action = args["action"]?.toString()
        val params = extractTemplateParams(args, setOf("appId", "action"))
        return crossAppLauncher.launchApp(appId, action, params)
    }

    /**
     * 执行 `cross_app__share_content`：Share Sheet 分享文本。
     *
     * **参数提取**：
     * - `content`（必需）：待分享文本
     */
    private suspend fun executeShareContent(args: Map<String, Any?>): String {
        val content = args["content"]?.toString()
            ?: return "缺少必需参数 content"
        return crossAppLauncher.shareContent(content)
    }

    /**
     * 执行 `cross_app__pick_media`：系统 Picker 选取媒体/文档。
     *
     * **参数提取**：
     * - `mediaType`（必需）：`photo` 或 `document`
     * - `mimeType`（可选）：MIME 类型
     * - `allowMultiple`（可选）：是否多选（仅文档有效）
     */
    private suspend fun executePickMedia(args: Map<String, Any?>): String {
        val mediaType = args["mediaType"]?.toString()
            ?: return "缺少必需参数 mediaType"
        val mimeType = args["mimeType"]?.toString()
        val allowMultiple = args["allowMultiple"] as? Boolean ?: false
        return crossAppLauncher.pickMedia(mediaType, mimeType, allowMultiple)
    }

    /**
     * 从 LLM arguments 中提取模板替换参数（排除已知标准字段）。
     *
     * 已知字段（如 `appId` / `action`）不参与模板替换，其余字段值转为 String
     * 供 [CrossAppLauncher.resolveTemplates] URL 编码后替换 scheme 中的 `{key}` 占位符。
     */
    private fun extractTemplateParams(
        args: Map<String, Any?>,
        knownKeys: Set<String>
    ): Map<String, String> {
        return args
            .filterKeys { it !in knownKeys }
            .mapValues { it.value?.toString() ?: "" }
    }

    companion object {
        /** 跨 App 工具命名空间前缀（ADR-016 5.4）。 */
        const val NAMESPACE_PREFIX = "cross_app__"

        /** 打开目标 App 工具名。 */
        const val TOOL_OPEN_APP = "${NAMESPACE_PREFIX}open_app"

        /** 分享内容工具名。 */
        const val TOOL_SHARE_CONTENT = "${NAMESPACE_PREFIX}share_content"

        /** 选取媒体工具名。 */
        const val TOOL_PICK_MEDIA = "${NAMESPACE_PREFIX}pick_media"

        /** 本执行器处理的所有工具名集合（O(1) 查表）。 */
        private val HANDLED_TOOLS = setOf(TOOL_OPEN_APP, TOOL_SHARE_CONTENT, TOOL_PICK_MEDIA)

        /**
         * 构建跨 App 工具的 [ToolDefinition] 列表（供 ConversationViewModel.buildTools 合并）。
         *
         * **工具描述**：description 中列出可用 appId 及其 actions，便于 LLM 决策调用。
         * 可用 appId 列表从 [CrossAppLauncher.getConfiguredApps] 动态生成。
         *
         * **JSON Schema**：parameters 使用 OpenAI 兼容的 JSON Schema 格式。
         * `cross_app__open_app` 允许 additionalProperties（模板参数动态）；
         * 其余工具严格定义字段。
         *
         * @param crossAppLauncher 已初始化的 CrossAppLauncher（用于读取可用 App 列表）
         * @return 3 个工具定义（open_app / share_content / pick_media）
         */
        fun buildToolDefinitions(crossAppLauncher: CrossAppLauncher): List<ToolDefinition> {
            val apps = crossAppLauncher.getConfiguredApps()
            val appListDesc = buildAppListDescription(apps)
            return listOf(
                buildOpenAppToolDefinition(appListDesc),
                buildShareContentToolDefinition(),
                buildPickMediaToolDefinition()
            )
        }

        /**
         * 构建 `cross_app__open_app` 工具定义。
         *
         * parameters 允许 additionalProperties（模板参数如 itemId/awemeId/uid 等动态字段）。
         */
        private fun buildOpenAppToolDefinition(appListDesc: String): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "appId" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("目标 App 标识符。可用值：$appListDesc"),
                        "enum" to JsonArray(
                            listOf("wechat", "alipay", "taobao", "douyin", "qq", "weibo", "baidu_map")
                                .map { JsonPrimitive(it) }
                        )
                    )),
                    "action" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "功能名（可选）。常用值：open（打开）/ scan（扫码）/ pay（支付）/ " +
                                "item（商品详情）/ detail（视频详情）/ route（导航）/ userinfo（用户主页）。" +
                                "不传时使用默认跳转"
                        )
                    ))
                )),
                "required" to JsonArray(listOf(JsonPrimitive("appId"))),
                "additionalProperties" to JsonPrimitive(true)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_OPEN_APP,
                    description = "打开手机上其他 App（Deep Link 跳转）。可传入 action 指定功能（如 scan 扫码），" +
                        "以及额外的模板参数（如 itemId 用于淘宝商品详情）。目标 App 未安装时返回降级提示。" +
                        "可用 App：$appListDesc",
                    parameters = parameters
                )
            )
        }

        /** 构建 `cross_app__share_content` 工具定义。 */
        private fun buildShareContentToolDefinition(): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "content" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("待分享的文本内容")
                    ))
                )),
                "required" to JsonArray(listOf(JsonPrimitive("content"))),
                "additionalProperties" to JsonPrimitive(false)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_SHARE_CONTENT,
                    description = "通过系统 Share Sheet 分享文本内容到其他 App（如微信、QQ、微博等）。" +
                        "会弹出系统分享选择器供用户选择目标 App。",
                    parameters = parameters
                )
            )
        }

        /** 构建 `cross_app__pick_media` 工具定义。 */
        private fun buildPickMediaToolDefinition(): ToolDefinition {
            val parameters = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "mediaType" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("选取类型：photo（照片）或 document（文档）"),
                        "enum" to JsonArray(listOf(
                            JsonPrimitive("photo"),
                            JsonPrimitive("document")
                        ))
                    )),
                    "mimeType" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("MIME 类型过滤（可选，如 image/png）。默认：photo 用 image/*，document 用 */*")
                    )),
                    "allowMultiple" to JsonObject(mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("是否允许多选（仅 document 有效，默认 false）")
                    ))
                )),
                "required" to JsonArray(listOf(JsonPrimitive("mediaType"))),
                "additionalProperties" to JsonPrimitive(false)
            ))
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_PICK_MEDIA,
                    description = "通过系统 Picker 选取照片或文档。选取 photo 时打开系统图片选择器，" +
                        "选取 document 时打开系统文档选择器（支持多选）。",
                    parameters = parameters
                )
            )
        }

        /**
         * 构建可用 App 列表描述（用于工具 description）。
         *
         * 格式：`wechat(微信), alipay(支付宝), taobao(淘宝), ...`
         */
        private fun buildAppListDescription(apps: List<AppSchemeEntry>): String {
            if (apps.isEmpty()) return "（无可用 App 配置）"
            return apps.joinToString(", ") { "${it.appId}(${it.displayName})" }
        }
    }
}
