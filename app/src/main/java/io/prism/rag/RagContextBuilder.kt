package io.prism.rag

import io.prism.data.RetrievalResult
import io.prism.ui.model.Citation

/**
 * RAG 上下文构建器（US-019，ADR-012 5.1 / 5.3）。
 *
 * 职责单一：把 [RetrievalResult] 列表转换为三件套：
 * 1. [systemPrompt]：RAG grounding rules（固定模板，约束 AI 引用规则与无引用降级话术）
 * 2. [buildContext]：检索片段拼接的 user context 文本（含 `[来源N]` 编号 + 文件/片段元信息）
 * 3. [buildCitations]：[Citation] 列表（供 UI 渲染引用胶囊）
 *
 * **不负责** embed / search（那是 [io.prism.ui.chat.ConversationViewModel] 的职责）。
 *
 * **线程安全**：object 单例 + 纯函数，无共享状态，可被任意协程并发调用。
 *
 * **citation 编号一致性**：[buildContext] 与 [buildCitations] 必须基于同一份 `results` 列表
 * 调用，且 [RetrievalResult] 列表顺序不变。两者均使用 `index = i + 1`（1-based），
 * 保证 prompt 中「[来源N]」与 UI 引用胶囊的编号严格对齐。
 */
object RagContextBuilder {

    /**
     * RAG grounding rules（ADR-012 5.3 模板）。
     *
     * **⚠️ 弃用（ADR-050，2026-09-12）：本常量已无生产注入点，请勿用于新的注入路径。**
     *
     * 原用途：客户端自动检索后注入 `【知识库片段】` 时配套声明四条作答纪律（优先使用片段 /
     * `[来源N]` 引用 / 无果时诚实降级 / 不捏造来源）。ADR-050 撤除自动注入后，"提供
     * 【知识库片段】"这一场景**在协议层不再发生**，继续注入会与实际行为不符
     * （BR-testing-014：行为契约文案不得失真）。
     *
     * 四条约束的**语义由 [buildToolGuidance] 第 3~5 条等价承接**（含"不引用未提供的片段编号"
     * 这一条）。保留本常量的理由：①`RagContextBuilderTest` 以它钉住引用格式与降级话术的措辞；
     * ②`buildContext`（工具侧边界文本）与它同源，未来若恢复"用户手势显式注入"（ADR-050 备选 B）
     * 需要同一套作答纪律。**若确定不再需要，应在专门批次连同测试一并删除**（不留半成品契约）。
     *
     * 约束 AI：
     * - 优先使用知识库片段回答
     * - 引用使用 [来源N] 格式
     * - 无引用时主动说明「知识库中未找到相关内容，以下回答基于模型自身知识」（US-019 AC-4）
     * - 不捏造来源
     *
     * **身份中立**（TASK-20260912-001，ADR-049 决策 D2）：本层原首句为「你是 Prism AI 助手。」
     * ——该断言**追加在 persona 基础身份层之后**，与角色包直陈身份（"你就是X"）构成同层矛盾：
     * 模型读到"你是 Prism AI 助手"后会退回"助手加载了角色设定"的框架，把 v5 的基础身份重排
     * 抵消（真机取证：角色会话中出现"我是 Prism AI 助手""我是一台 Android 语音助手"）。
     * 检索规则层**不应重复声明身份**（身份由 persona 层唯一承载），故改为身份中立的规则标题，
     * 四条约束语义逐字保留、出厂行为等价。
     */
    const val SYSTEM_PROMPT: String = """【知识库检索规则】当提供【知识库片段】时，请遵循：
1. 优先使用【知识库片段】中的信息回答问题
2. 引用知识库片段时使用 [来源N] 格式，N 为片段编号
3. 若知识库片段未提供答案，明确说明「知识库中未找到相关内容，以下回答基于模型自身知识」
4. 不捏造来源，不引用未提供的片段编号"""

    /**
     * ADR-050（TASK-20260912-002）：知识库**能力声明 + 工具使用规则**（systemPrompt 层）。
     *
     * **与 [SYSTEM_PROMPT] 的关系**：后者的四条约束是"资料已提供时"的作答纪律，其**语义已并入
     * 本函数第 3~5 条逐条等价承接**（优先使用检索结果 / `[来源N]` 引用格式 / 检索无果时的诚实
     * 降级话术 / 不捏造来源且不引用未提供的片段编号）。本函数额外声明**何时该调用工具**与
     * **否定约束**——两者共同构成 RAG 层的完整内容。
     *
     * **为什么不再注入 [SYSTEM_PROMPT]**：ADR-050 后客户端不再自动注入 `【知识库片段】`，
     * 该常量描述的场景（"当提供【知识库片段】时"）在协议层已不再发生，若继续注入会与实际行为
     * 不符（属 BR-testing-014 所指的"行为契约文案失真"）。该常量**保留但不再被生产代码注入**
     * （见其 KDoc 的弃用说明），其约束语义由本函数承接。
     *
     * **为什么需要显式声明**：知识库能力对 LLM 的唯一可见通道曾是 tools 数组里的工具描述；
     * 真机实证（考古 H1，见 `docs/reports/2026-08-15-uxr4-archaeology.md`）显示仅凭工具描述时，
     * 用户措辞未精确命中触发词会让模型从不调用（hotlist 同因，见 `HOTLIST_GUIDANCE` 先例）。
     * 故显式声明能力 + 触发场景 + **否定约束**（不需要时不查）。
     *
     * **前缀缓存友好**：文案唯一变量是 [scope]，只随用户切换知识库模式变化，不随对话轮次
     * 变化（对比 ADR-050 撤除的"每轮注入检索片段"——后者使请求前缀每轮失效）。
     *
     * **不声明身份**（对齐 [SYSTEM_PROMPT] 的身份中立约定）：检索规则层不得重复声明身份，
     * 身份由 persona 层唯一承载（TASK-20260912-001 / ADR-049 D2）。
     *
     * @param scope 检索范围的人类可读描述（如"全部知识库"、"指定知识库（knowledgeBaseId=3）"）
     * @return 能力声明文本（稳定、不含任何知识库正文）
     */
    fun buildToolGuidance(scope: String): String = """【知识库能力】你可以调用知识库工具检索用户已上传的个人资料（当前检索范围：$scope）：
1. 用户的问题可能与其资料有关时（询问已上传文档的内容、需要依据资料作答、确认资料里有没有某项信息），先调用 knowledge_base__search 检索，再依据检索结果回答
2. 需要了解资料清单时调用 knowledge_base__list_documents；需要某篇文档完整内容时调用 knowledge_base__get_document_content
3. 优先使用检索结果中的信息回答，不要用你自己的猜测替代资料内容
4. 引用资料时使用 [来源N] 格式，N 为返回结果的编号；只引用工具实际返回的编号，不捏造来源、不引用未提供的编号
5. 检索结果未提供答案时明确说明「知识库中未找到相关内容，以下回答基于模型自身知识」
6. 与用户资料无关的通用问题（闲聊、写作、常识、代码、时事等）不要调用知识库工具——知识库内容只在你主动调用工具后才进入上下文"""

    /**
     * 把检索结果列表拼接为 RAG context 文本。
     *
     * 输出格式：
     * ```
     * 【知识库片段】
     * [来源1] 文件=文档A.pdf 片段=1
     * 内容...
     *
     * [来源2] 文件=文档B.md 片段=3
     * 内容...
     * 【END 知识库片段】
     * ```
     *
     * @param results 检索结果列表（按相似度降序，调用方已做阈值过滤）
     * @return 拼接后的 context 文本；results 为空时返回空串（调用方应据此跳过 ragContext 注入）
     */
    fun buildContext(results: List<RetrievalResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【知识库片段】\n")
        results.forEachIndexed { i, r ->
            val index = i + 1
            sb.append("[来源$index] ")
            sb.append(buildChunkInfo(r))
            sb.append('\n')
            sb.append(r.content)
            sb.append("\n\n")
        }
        sb.append("【END 知识库片段】")
        return sb.toString()
    }

    /**
     * 把检索结果列表转换为 [Citation] UI 列表。
     *
     * @param results 检索结果列表（必须与 [buildContext] 同一份，保证编号对齐）
     * @return Citation 列表；results 为空时返回空列表
     */
    fun buildCitations(results: List<RetrievalResult>): List<Citation> =
        results.mapIndexed { i, r ->
            Citation(
                index = i + 1,
                documentTitle = r.documentTitle,
                chunkIndex = r.chunkIndex,
                similarity = r.similarity
            )
        }

    /** 构建单条检索结果的元信息行（`文件=xxx 片段=N`）。chunkIndex 为 null 时省略片段段。 */
    private fun buildChunkInfo(r: RetrievalResult): String {
        val chunkPart = r.chunkIndex?.let { " 片段=$it" } ?: ""
        return "文件=${r.documentTitle}$chunkPart"
    }
}
