package io.prism.rag

/**
 * RAG 检索目标（US-019，ADR-012 5.2）。
 *
 * 表达用户在对话页切换的三态 RAG 模式：
 * - [Off]：关闭 RAG，普通对话
 * - [AllLibraries]：全库检索（默认），`kbId=null` 跨所有库检索
 * - [SpecificLibrary]：指定库检索，`kbId` 为具体库 id（>0）
 *
 * **与 [io.prism.data.KnowledgeBaseRepository.search] 的 kbId 参数对齐**：
 * - [AllLibraries] → `kbId=null`
 * - [SpecificLibrary] → `kbId=具体库id`
 * - [Off] → 不调用 search
 *
 * **持久化**：US-019 范围内仅内存 StateFlow 暴露，DataStore 持久化延后（ADR-012 5.2 备注）。
 * 默认值 [AllLibraries]（ADR-012 5.2「默认开启」）。
 *
 * **G-04 修复（guardrail TKN-US019-RAG-GUARDRAIL-001）**：[SpecificLibrary] 入参校验 `kbId > 0`，
 * 与 KDoc 一致；`0L` 是默认库虚拟 id（用 [AllLibraries] 检索默认库），负数非法。
 */
sealed interface RagTarget {
    /** 关闭 RAG，普通对话 */
    object Off : RagTarget

    /** 全库检索（默认） */
    object AllLibraries : RagTarget

    /**
     * 指定库检索。
     *
     * @throws IllegalArgumentException 当 [kbId] <= 0 时（init 块校验，G-04 修复）
     */
    data class SpecificLibrary(val kbId: Long) : RagTarget {
        init {
            // G-04 修复：KDoc 声称 kbId > 0，代码强制校验。
            // 0L 是默认库虚拟 id（应用 AllLibraries 检索默认库），负数非法。
            require(kbId > 0) {
                "SpecificLibrary kbId 必须 > 0（收到 $kbId）；默认库请用 AllLibraries，负数非法"
            }
        }
    }
}
