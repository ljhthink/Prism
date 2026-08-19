package io.prism.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 联网搜索本地工具执行器（问题 8b，ADR-020）—— 实现 [LocalToolExecutor] 接口。
 *
 * **背景**：用户问题 8 要求补充「联网搜索」能力。选型结论（ADR-020）：
 * - **Bing RSS 端点**（`https://cn.bing.com/search?q=<q>&format=rss`）：国内可访问、
 *   无需 API Key、返回结构化 RSS XML（title/link/description/pubDate），
 *   契合「本地内置、零配置」定位
 * - 否决 DuckDuckGo（国内被墙，实测不可达）；否决需 Key 的 Bing/Google/Brave API（违背零配置）
 *
 * **工具**：`web_search__search`（`web_search__` 命名空间，与 `cross_app__` 平行）。
 * - 参数：`query`（必需，搜索关键词）+ `maxResults`（可选，1..10，默认 5；
 *   传 8-10 表示需要全面覆盖多来源，自动触发互补查询合并，去重后最多 16 条）
 * - 返回：序号 + 标题 + 链接 + 摘要（纯文本，回灌给 LLM 作事实依据，头部含
 *   内联 [N] 引用要求，O5/PRD UXR8）
 *
 * **RSS 解析**：轻量正则提取 `<item>` 块（title/link/description），HTML 实体解码 +
 * 去标签 + 截断。不引入 XML 解析依赖（Karpathy 简洁原则；避免 R8 keep 风险）。
 *
 * **降级策略**（与 MCP callTool 一致）：所有失败场景返回描述性字符串（而非抛异常），
 * 由 SkillExecutor 作为 tool result 回灌给 LLM，让 LLM 决定如何降级。
 *
 * **协程取消**（BR-error-handling-007）：CancellationException 重抛，不吞。
 *
 * **安全边界**：
 * - 查询词经 [URLEncoder] 编码（SSRF/注入防御，不拼接裸用户输入到 URL）
 * - 返回结果仅含 Bing 网页摘要（第三方内容，长度截断防 token 溢出 + 防 prompt 注入面）
 * - User-Agent 头设置（Bing 对无 UA 的自动化请求可能降级/拒绝）
 *
 * **可测性**（BR-testing-004）：通过 [httpClient] 注入解耦，测试可注入 MockEngine
 * 返回 canned RSS，纯 JVM 验证解析逻辑。
 *
 * @param httpClient 用于搜索请求的 Ktor HttpClient（建议独立 client：无 SSE 插件 + HttpTimeout）
 */
class WebSearchLocalToolExecutor(
    private val httpClient: HttpClient
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_SEARCH

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            // M-2 修复（guardrail TKN-P8-GUARDRAIL-001）：空/空白 query fail-fast（BR-error-handling-004），
            // 避免发出空查询浪费请求并返回误导性结果
            val query = arguments["query"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext "缺少必需参数 query"
            val maxResults = (arguments["maxResults"] as? Number)
                ?.toInt()?.coerceIn(MIN_RESULTS, MAX_RESULTS) ?: DEFAULT_MAX_RESULTS

            try {
                // G-03 修复（guardrail TKN-UXR8-B2-GUARDRAIL-001，BR-performance-002）：
                // 本工具被 SkillExecutor `withTimeout(30s)` 包裹（DEFAULT_TOOL_TIMEOUT_MS），
                // 而串行子请求（主查询 + 降级重试 ≤3 / 合并变体 ≤2）每次最长 10s
                // （searchHttpClient requestTimeoutMillis）。无预算感知时子请求耗时之和
                // 可达 40s/30s 贴满或超出总超时 → 外层取消导致**已成功的主结果被整体丢弃**。
                // 修复：每次发起新子请求前检查剩余预算，不足则跳过并返回已完成部分。
                val startNanos = System.nanoTime()
                fun hasBudgetForAnotherRequest(): Boolean =
                    Companion.hasRequestBudget((System.nanoTime() - startNanos) / 1_000_000L)

                // UXR7 问题 1（根本性根因）：Bing 对冷门中文新词在**长 query** 中分词失败
                //（如"昔涟 是谁"→ 分词收窄为"昔"，实测单个"昔涟"可整词匹配返回正确结果）。
                // mkt/setlang 参数与引号均无法影响服务端分词（网络调研实证：SearXNG #4964
                // 同机制、阿里云 OpenSearch re_search 官方机制、jieba/HanLP 核心词提取）。
                // 修复（UXR7-R2）：提取 query 的全部候选核心中文词（多候选，按出现顺序、
                // 跳过停用词），若主搜索结果**不含任一候选核心词**（分词失败判据），
                // 依次用候选核心词**短整词降级重试**（实测单独短整词命中率最高）。
                // LOW-01（guardrail TKN-UXR7R2-GUARDRAIL-001）：候选数截断前 N 个，
                // 防止极端超长 query 产生过多候选放大串行网络请求。
                val coreTerms = extractCoreTerms(query).take(MAX_CORE_TERM_RETRIES)
                // UXR9 Bug2 回归修复：主查询结果先做条目级过滤（丢弃仅命中单字"昔"等
                // 分词坍缩噪声条目），再判相关性——混合集不再整体放行，噪声不进入引用来源。
                val primaryItems = filterRelevantItems(fetchSearch(query), coreTerms)
                val primaryRelevant = primaryItems.isNotEmpty() &&
                    (coreTerms.isEmpty() || isRelevant(primaryItems, coreTerms))

                if (coreTerms.isNotEmpty() && !primaryRelevant) {
                    // 主结果不相关（Bing 长 query 分词坍缩）→ 依次用候选核心词短整词重试。
                    // 只重试"不同于原 query"的候选（避免与主查询重复）。
                    for (term in coreTerms) {
                        if (term == query) continue
                        // G-03：预算不足时停止重试，直接落入失败文案（不把外层 30s 拖穿）
                        if (!hasBudgetForAnotherRequest()) {
                            Log.i(LOG_TAG, "core term retry skipped: budget exhausted")
                            break
                        }
                        // DEF-002（ac-verifier TKN-UXR7R2-ACCEPTANCE-001）：核心词同样可能含
                        // 用户 PII，日志截断后再记录（对齐 LOW-03 CWE-532 处理）。
                        Log.w(LOG_TAG, "primary search irrelevant for core=${term.take(LOG_QUERY_MAX_LEN)}, retrying with core term")
                        val retryItems = fetchSearch(term)
                        if (retryItems.isNotEmpty() && isRelevant(retryItems, listOf(term))) {
                            // UXR9 Bug2：重试结果同样做条目级过滤，丢弃仅含单字/子串的噪声条目
                            return@withContext formatSearchResult(filterRelevantItems(retryItems, listOf(term)), maxResults)
                        }
                    }
                    // 所有核心词重试仍不相关 → 兜底切百度（专救中文实体：Bing 对"梧州一中"消歧为城市）。
                    // 百度仍不相关才判定"搜索失败"（触发 SkillExecutor 重复工具熔断）。
                    Log.w(LOG_TAG, "all core term retries irrelevant for query=${query.take(LOG_QUERY_MAX_LEN)}, try baidu")
                    tryBaiduFallback(query, coreTerms, maxResults, { (System.nanoTime() - startNanos) / 1_000_000L })
                        ?.let { return@withContext it }
                    return@withContext "搜索失败：未找到与「$query」相关的网页结果"
                }
                if (primaryItems.isEmpty()) {
                    // 空结果：百度兜底后再判中性文案（不诱导 LLM 以同义 query 反复重试）。
                    Log.w(LOG_TAG, "web search empty result for query=${query.take(LOG_QUERY_MAX_LEN)}, try baidu")
                    tryBaiduFallback(query, coreTerms, maxResults, { (System.nanoTime() - startNanos) / 1_000_000L })
                        ?.let { return@withContext it }
                    return@withContext "搜索失败：未找到与「$query」相关的网页结果"
                }

                // ==================== O5 扩容路径（PRD UXR8）====================
                // LLM 请求 ≥ MERGE_TRIGGER_RESULTS 条 = 明确要求全面覆盖多来源。Bing RSS
                // 单查询恒返 ≤10 条（实测 count/first 参数被忽略、无分页），通过互补核心词
                // 变体查询补源：主查询 + ≤MAX_MERGE_QUERIES 个变体（变体结果按核心词做
                // item 级过滤，只并入真正相关的新来源），归一化 URL 去重，总上限
                // MERGE_MAX_RESULTS（防 token 溢出）。串行请求预算 ≤3（对齐
                // MAX_CORE_TERM_RETRIES 思路）。变体查询失败不拖垮主结果（仅放弃补充来源）。
                if (maxResults >= MERGE_TRIGGER_RESULTS) {
                    val variants = coreTerms.filter { it != query }.take(MAX_MERGE_QUERIES)
                    if (variants.isNotEmpty()) {
                        val merged = mutableListOf<SearchItem>()
                        val seenUrls = mutableSetOf<String>()
                        for (item in primaryItems) {
                            if (seenUrls.add(normalizeUrl(item.link))) merged.add(item)
                        }
                        for (term in variants) {
                            if (merged.size >= MERGE_MAX_RESULTS) break
                            // G-03（BR-performance-002）：预算不足时放弃补充来源，返回主结果
                            //（变体是增益项，禁止把外层 30s 拖穿导致已成功的主结果被丢弃）
                            if (!hasBudgetForAnotherRequest()) {
                                Log.i(LOG_TAG, "merge variant skipped: budget exhausted, keep primary=${merged.size}")
                                break
                            }
                            Log.i(LOG_TAG, "merge variant query for more sources: core=${term.take(LOG_QUERY_MAX_LEN)}")
                            val variantItems = try {
                                fetchSearch(term)
                            } catch (e: CancellationException) {
                                throw e // BR-error-handling-007：协程取消必须重抛
                            } catch (e: Exception) {
                                Log.w(LOG_TAG, "variant query failed, skip: ${e::class.simpleName} core=${term.take(LOG_QUERY_MAX_LEN)}")
                                emptyList()
                            }
                            for (item in variantItems) {
                                if (merged.size >= MERGE_MAX_RESULTS) break
                                if ((item.title.contains(term) || item.snippet.contains(term)) &&
                                    seenUrls.add(normalizeUrl(item.link))
                                ) {
                                    merged.add(item)
                                }
                            }
                        }
                        if (merged.size > primaryItems.size) {
                            Log.i(LOG_TAG, "merged search sources: primary=${primaryItems.size} merged=${merged.size}")
                            return@withContext formatSearchResult(merged, MERGE_MAX_RESULTS)
                        }
                        // 变体未带来新来源 → 回退单查询结果（不无谓多占 token）
                    }
                }
                formatSearchResult(primaryItems, maxResults)
            } catch (e: CancellationException) {
                throw e // BR-error-handling-007：协程取消必须重抛
            } catch (e: Exception) {
                // 网络/解析失败降级为描述性文案（不泄露内部细节，CWE-209）
                Log.w(LOG_TAG, "web search failed: ${e::class.simpleName} query=${query.take(LOG_QUERY_MAX_LEN)}")
                // UXR6 问题 1：中性失败文案，删除"请稍后重试"（旧文案诱导 LLM 反复调用
                // 同一工具直至 maxRounds=10 上限）。前置 `[搜索失败]` 标记供
                // SkillExecutor.isFailureResult 识别并触发重复工具熔断。
                "搜索失败：联网搜索暂不可用，请基于已有信息回答"
            }
        }

    /**
     * 执行一次 Bing 搜索请求并解析结果（UXR7 抽取，供原查询 + 核心词降级重试复用）。
     *
     * **v1 批次6（Issue 2，真机证据）由 RSS 改 HTML SERP**：Bing `format=rss` 对中文实体
     * （如"梧州市第一中学"）排名明显坍缩——实证连**精确校名**都只返回市级百科，造成
     * "参考来源大概相关、与内容无直接联系"。改用 Bing **HTML 搜索结果页**（`li.b_algo`
     * 结构化块），实测同查询能直接命中学校官网/词条。HTML 端点 Bing 反爬中等（较 Google
     * 宽松），配合浏览器 UA / Accept-Language 即可稳定解析（tech-selection-researcher，
     * 2026-08-19）。
     *
     * @param query 搜索关键词（Bing 自动 URL 编码）
     * @return 解析后的搜索结果列表（可能为空；返回空壳/被拦页时也返回空）
     */
    private suspend fun fetchSearch(query: String): List<SearchItem> {
        val response: HttpResponse = httpClient.get(BING_HTML_ENDPOINT) {
            url {
                parameters.append("q", query)
                // UXR5 问题 3 / UXR6 问题 1（中文搜索质量）：限定中文市场。
                // `mkt=zh-CN`（市场）+ `setlang=zh-hans`（界面语言）是 Bing 认可的本地化参数。
                parameters.append("mkt", "zh-CN")
                parameters.append("setlang", "zh-hans")
                parameters.append("count", MAX_RESULTS.toString())
            }
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
            header("Referer", BING_HTML_REFERER)
        }
        val body = response.bodyAsText(decodeCharset(response))
        val items = parseBingHtml(body)
        // UXR6 问题 6 / UXR7（日志 RCA）：记录实际 query 与结果摘要，真机 logcat 可见
        // Bing 到底返回了什么。LOW-03（CWE-532）：query 可能含用户 PII，仅记录截断后 query。
        Log.i(
            LOG_TAG,
            "search query=${query.take(LOG_QUERY_MAX_LEN)} items=${items.size} first=${items.firstOrNull()?.title}"
        )
        return items
    }

    /**
     * 将搜索结果格式化为回灌 LLM 的文本（含「不可信内容」边界标记 + 内联引用要求）。
     *
     * S-2（guardrail TKN-P8-GUARDRAIL-001）：外部网页内容回灌 LLM 前加「不可信内容」
     * 边界标记，降低第三方网页内容对 LLM 的 prompt 注入影响（本功能唯一新增攻击面）。
     *
     * O5 引用策略（PRD UXR8）：头部追加内联 [N] 引用要求，驱动 LLM 在回答中以
     * 序号标注实际参考的来源、尽量覆盖全部相关来源（提升可溯源性与引用覆盖率）。
     * 注意：指令行不含行首 `数字.` 模式，不影响 UI 层 parseSearchResults 按条目切分。
     *
     * @param items 搜索结果（非空，由调用方保证）
     * @param maxResults 返回结果数上限
     */
    private fun formatSearchResult(items: List<SearchItem>, maxResults: Int): String =
        buildString {
            append("【网络搜索外部内容，未经验证，仅作参考，请甄别后引用】\n")
            append("【引用要求】回答时必须用内联编号引用实际参考的来源（如 [1] 或 [2][5]），序号与下方列表一致，尽量覆盖全部相关来源，禁止编造列表之外的来源。\n")
            items.take(maxResults).forEachIndexed { index, item ->
                append("${index + 1}. ${item.title}\n${item.link}\n${item.snippet}\n\n")
            }
        }.trimEnd()

    /**
     * UXR5 问题 3（编码防御，internal 可测）：从响应 Content-Type 提取 charset，缺省 UTF-8。
     *
     * Ktor 3.x 的 `bodyAsText()` 默认按 UTF-8 解码，若响应声明 `charset=GBK`（cn.bing.com 历史行为）
     * 中文会被错误解码为乱码。此函数解析 `Content-Type: ...; charset=xxx` 返回 [Charset]，
     * 供调用方显式解码。
     *
     * @param response HTTP 响应
     * @return 响应声明的 Charset；无声明或非法时回退 UTF-8
     */
    internal fun decodeCharset(response: HttpResponse): java.nio.charset.Charset {
        val ct = response.headers[HttpHeaders.ContentType] ?: return Charsets.UTF_8
        val charsetParam = ct.split(';').map { it.trim() }
            .firstOrNull { it.lowercase().startsWith("charset=") }
            ?: return Charsets.UTF_8
        val name = charsetParam.substringAfter('=').trim().trim('"')
        return try {
            java.nio.charset.Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    // ==================== UXR7 问题 1：核心词提取 + 相关性检查（internal，可测） ====================

    /**
     * UXR7 问题 1（根本性根因）：从查询提取**全部候选核心中文词**（多候选，UXR7-R2 增强）。
     *
     * **背景**：Bing 对冷门中文新词（如"昔涟"）在**长 query** 中分词失败——实测
     * "昔涟 是谁"返回"昔"相关（分词收窄为"昔"），而**单个"昔涟"可整词匹配返回正确结果**。
     * 网络调研（SearXNG #4964 同机制、阿里云 OpenSearch re_search 官方机制）证实这是
     * 服务端 query-understanding 对 OOV 冷词的通用行为，客户端参数无法修复，只能
     * **客户端提取核心词 + 短整词降级重试**绕开。
     *
     * **多候选原因**：单候选（仅取第一个中文片段）在多实体 query（如"昔涟 星穹铁道 角色"）
     * 下可能取到次要实体，导致重试仍不命中。因此返回**全部** ≥2 字非停用词连续中文片段
     *（按出现顺序，LLM 通常把主实体放 query 最前），execute 依次重试，任一命中即返回。
     *
     * guardrail M-1（TKN-UXR7-GUARDRAIL-001）：跳过常见中文停用词（"最新""新闻""是谁"等），
     * 避免 "DeepSeek 最新 新闻" 误取"最新"为实体触发无谓的降级重试。
     *
     * @param query 用户/LLM 传入的搜索关键词
     * @return 核心中文词候选列表（按出现顺序，已过滤停用词与重复）；无 ≥2 字非停用词
     *         连续中文片段（如纯英文/仅停用词）时返回空列表
     */
    internal fun extractCoreTerms(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        // v1 真机二次修复（ADR-038）：对无空格/标点的中文句（如"梧州一中是什么学校"），正则
        // `[\u4e00-\u9fff]{2,}` 会把整句当成**一个**连续 CJK run，导致核心词=整句、条目过滤
        // 与相关性判定都要求字面整句命中，检索只返回"大概相关"而非直接命中；且降级重试用
        // `term == query` 跳过了唯一候选（整句）→ 无真实降级。修复：对每个 run 再**剥尾部
        // 疑问/泛化后缀**衍生一个前置实体候选（"是什么学校/怎么/如何/…"剥掉 → "梧州一中"）。
        val terms = LinkedHashSet<String>()
        for (run in Regex("""[\u4e00-\u9fff]{2,}""").findAll(query).map { it.value }) {
            if (run.isBlank()) continue
            if (run !in CHINESE_STOP_WORDS) terms.add(run)
            val entity = stripTrailingQuerySuffix(run)
            if (entity.length >= 2 && entity != run && entity !in CHINESE_STOP_WORDS) {
                terms.add(entity)
            }
        }
        return terms.take(MAX_CORE_TERM_RETRIES.coerceAtLeast(4)).toList()
    }

    /**
     * 剥除连续中文 run 尾部的疑问/泛化后缀，返回前置实体候选。
     *
     * "梧州一中是什么学校" → "梧州一中"；"如何配置MCP服务器"的 CJK 片段"如何配置"→ 空。
     * 最长后缀优先（按长度降序），逐一剥除直到无匹配或过短。纯函数可测。
     */
    private fun stripTrailingQuerySuffix(run: String): String {
        var s = run
        while (s.length >= 3) {
            // 取所有命中的后缀中最长者（不依赖列表顺序），确保最长匹配优先剥除
            val suffixed = QUERY_SUFFIXES.filter { s.endsWith(it) }.maxByOrNull { it.length }
            if (suffixed == null || suffixed.length >= s.length) break
            s = s.dropLast(suffixed.length)
        }
        return s
    }

    /** 中文 query 尾部疑问/泛化后缀（按长度降序，保证最长匹配优先剥除）。 */
    private val QUERY_SUFFIXES = listOf(
        "是什么学校", "是什么东西", "是什么样的", "是怎么回事",
        "是什么情况", "到底是什么", "是什么时候", "为什么要", "怎么做",
        "是什么", "是不是", "是怎么", "怎么弄", "怎么样", "怎么办",
        "如何", "怎么", "哪种", "哪种的", "哪个", "哪些", "什么样",
        "多少", "呢", "吗", "呀", "啊", "的了", "的"
    )

    /**
     * 常见中文搜索停用词（guardrail M-1，TKN-UXR7-GUARDRAIL-001，UXR7-R2 扩充）。
     *
     * 这些词常见于 LLM 生成的附加 query（"X 最新 新闻"），非用户要搜的实体，
     * 若被 [extractCoreTerms] 误取为核心词会导致无谓降级重试。
     *
     * **UXR7-R2 扩充**（网络调研：jieba/HanLP 常用停用词表）：补充"角色""游戏""大全"等
     * LLM 常附加在实体后的通用词——真机日志 query "昔涟 角色 / 昔涟 星穹铁道 角色"
     * 中"角色"若被误取会先重试"角色"而非"昔涟"（虽不致命，但浪费一次请求且可能返回
     * 泛化结果）。网络调研建议配合 jieba 级词典，本项目按 Karpathy 简洁原则以内置表实现。
     */
    private val CHINESE_STOP_WORDS = setOf(
        "今天", "昨天", "明天", "最新", "新闻", "消息", "是谁", "是什么", "什么", "怎么",
        "为什么", "多少", "哪个", "哪些", "如何", "怎样", "介绍", "信息", "详情", "内容",
        "资料", "情况", "相关", "价格", "怎么样", "时候", "时间", "现在", "目前", "意思",
        "含义", "区别", "比较", "功能", "特点", "玩法", "攻略", "最新消息", "什么时候",
        // UXR7-R2 扩充：LLM 常附加在实体后的通用词（网络调研 jieba 停用词表启发）
        "角色", "游戏", "大全", "图片", "视频", "下载", "官网", "首页", "百科", "百度"
    )

    /**
     * UXR7 问题 1：判断搜索结果是否与核心词相关（分词失败判据，UXR7-R2 支持多候选）。
     *
     * 若搜索结果的所有 title/snippet 都**不含任一核心词**，判定 Bing 分词失败
     *（返回了与用户实体无关的结果）。核心词列表为空时视为"无法判断，默认相关"。
     *
     * @param items 搜索结果列表
     * @param coreTerms 核心中文词候选列表（[extractCoreTerms] 的返回值，可为空）
     * @return true 表示结果相关（或无法判断）；false 表示分词失败（结果不相关）
     */
    internal fun isRelevant(items: List<SearchItem>, coreTerms: List<String>): Boolean {
        if (coreTerms.isEmpty()) return true
        return items.any { item ->
            coreTerms.any { term -> item.title.contains(term) || item.snippet.contains(term) }
        }
    }

    /**
     * 条目级过滤（UXR9 Bug2 回归修复，TKN-UXR9-ARCHAEOLOGY-001）：仅保留含**任一完整核心词**
     * 的结果条目。
     *
     * **背景**：Bing 对冷门中文新词（如"昔涟"）在长 query 中分词坍缩，可能返回**混合集**——
     * 部分条目含完整"昔涟"，部分条目仅命中单字"昔"（噪声）。此前 [isRelevant] 是**集合级 any**
     * 判定（任一条目含任一核心词即通过），混合集整体放行，导致"昔"单字噪声条目混入引用来源
     *（UXR9 真机实测："多篇返回的网络资料均是只有'昔'一个字的资料"）。
     *
     * 本函数按**条目**过滤：title+snippet 拼接后含任一 ≥2 字核心词才保留。核心词为空
     *（纯英文查询 / 无法提取）时不过滤，返回原列表（避免误杀英文相关结果）。
     *
     * @param items 搜索结果列表
     * @param coreTerms 核心中文词候选列表（[extractCoreTerms] 的返回值，可为空）
     * @return 过滤后的条目（仅含核心词命中的；coreTerms 为空时原样返回）
     */
    internal fun filterRelevantItems(items: List<SearchItem>, coreTerms: List<String>): List<SearchItem> {
        if (items.isEmpty() || coreTerms.isEmpty()) return items
        return items.filter { item ->
            val haystack = item.title + "\n" + item.snippet
            coreTerms.any { term -> term.length >= 2 && haystack.contains(term) }
        }
    }

    /**
     * O5（PRD UXR8）：搜索结果 URL 归一化（多查询合并去重的判据，internal 可测）。
     *
     * 同一网页在主查询与变体查询中常以微小差异重复出现（跟踪参数 utm_*、www 前缀、
     * 尾部斜杠、fragment、大小写混用的 scheme/host），直接按原文判重会漏。归一化规则：
     * 1. 去 fragment（`#...`）；
     * 2. 剔除常见跟踪参数（`utm_*` 前缀 + spm/scm/ref/referrer，保留语义参数）；
     * 3. scheme/host 小写 + 去 `www.` 前缀（path 保留原大小写——URL 路径大小写敏感）；
     * 4. 去尾部斜杠。
     *
     * @param link 搜索结果链接原文
     * @return 归一化 URL（非 http(s) 链接原样返回，仅去尾斜杠）
     */
    internal fun normalizeUrl(link: String): String {
        var url = link.trim().substringBefore('#')
        val queryIndex = url.indexOf('?')
        if (queryIndex >= 0) {
            val base = url.substring(0, queryIndex)
            val keptParams = url.substring(queryIndex + 1).split('&')
                .filter { param ->
                    val key = param.substringBefore('=').lowercase()
                    key.isNotEmpty() && !key.startsWith("utm_") && key !in TRACKING_PARAM_KEYS
                }
            url = if (keptParams.isEmpty()) base else base + "?" + keptParams.joinToString("&")
        }
        val match = URL_SCHEME_HOST_REGEX.find(url) ?: return url.removeSuffix("/")
        val (scheme, host, path) = match.destructured
        return scheme.lowercase() + "://" + host.lowercase().removePrefix("www.") + path.removeSuffix("/")
    }

    // ==================== RSS 解析（internal，可测） ====================

    /** 单个搜索结果（标题 / 链接 / 摘要）。 */
    internal data class SearchItem(
        val title: String,
        val link: String,
        val snippet: String
    )

    /**
     * 从 Bing RSS 响应体提取搜索结果列表（纯函数，可测）。
     *
     * **解析策略**：Bing RSS 的每个 `<item>` 块含 `<title>` / `<link>` / `<description>`。
     * - 正则分割 `<item>...</item>` 块（非贪婪跨行）
     * - 块内提取 title / link / description
     * - 三者均经 [decodeHtmlEntities] 解码 + [stripHtmlTags] 去标签 + 截断
     *
     * @param body Bing RSS 响应体原文
     * @return 搜索结果列表（已按出现顺序，含空 item 过滤）
     */
    internal fun parseRssItems(body: String): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        val itemRegex = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        for (match in itemRegex.findAll(body)) {
            val block = match.groupValues[1]
            val title = extractTag(block, "title")?.let { clean(it) } ?: continue
            val link = extractTag(block, "link")?.let { clean(it) } ?: continue
            val snippet = extractTag(block, "description")?.let { clean(it) } ?: ""
            items.add(SearchItem(title = title, link = link, snippet = snippet))
        }
        return items
    }

    /**
     * 从 Bing **HTML 搜索结果页**解析 `li.b_algo` 结构化块（v1 批次6 Issue 2 主路径）。
     *
     * **为什么不用 RSS**：真机证据显示 Bing `format=rss` 对中文实体排名坍缩（精确校名返回市级
     * 百科）；HTML SERP 的 `li.b_algo` 块带真实标题/链接/摘要，实测能命中学校官网。本函数基于
     * Bing SERP 稳定结构（`li.b_algo` + `h2 a` + `cite`/`b_caption`，详见 tech-selection-researcher
     * 调研）以正则提取，**不引入 HTML 解析依赖**（Karpathy 简洁 + 避免 R8 风险）。
     *
     * **链接处理**：Bing 结果 `href` 有直接地址（多数）与 `//cn.bing.com/ck/a?...u=a1<base64>`
     * 跳转两种；后者经 [decodeBingUrl] 解码出真实 URL。
     *
     * @param body Bing HTML 响应体原文
     * @return 搜索结果列表（按出现顺序；无 `b_algo` 块即视为空壳/被拦页 → 空）
     */
    internal fun parseBingHtml(body: String): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        val algoRegex = Regex("""<li class="b_algo"[\s\S]*?</li>""", RegexOption.IGNORE_CASE)
        for (match in algoRegex.findAll(body)) {
            val block = match.value
            // 真实链接：取 h2 内首个 a 的 href；Bing 跳转链接再解码
            val href = Regex("""<a[^>]+href="([^"]+)""")
                .find(block)?.groupValues?.get(1) ?: continue
            val link = decodeBingUrl(href.trim())
                .takeIf { it.startsWith("http") } ?: continue
            // 标题：`<h2>...<a>标题</a>...</h2>`（去标签 + 解码）
            val title = Regex("""<h2[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.let { clean(it) } ?: continue
            // 摘要：`b_lineclamp` 的 `<p>`；缺省回退 `b_caption` 块
            val snippet = Regex(
                """<p[^>]*class="[^"]*b_lineclamp[^"]*"[^>]*>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.get(1)
                ?: Regex("""<div class="b_caption"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                    .find(block)?.groupValues?.get(1).orEmpty()
            items.add(SearchItem(title = title, link = link, snippet = clean(snippet)))
        }
        return items
    }

    /**
     * 解码 Bing 结果链接（纯函数可测）：直链直接采用；`//cn.bing.com/ck/a?...` 跳转链接从
     * `u=a1<base64url>` 解出真实 URL（`a1aHR0c` = base64("https")，`aHR0c` = base64("http")）。
     *
     * @param href 原始 href 属性值（可能以 `//` 开头）
     * @return 真实绝对 URL；无法解析返回空串
     */
    internal fun decodeBingUrl(href: String): String {
        val h = href.trim().let { if (it.startsWith("//")) it.removePrefix("//") else it }
        val isCkLink = h.startsWith("cn.bing.com/ck/") || h.startsWith("bing.com/ck/")
        if (!isCkLink) {
            // 直链：补全 scheme
            return if (h.startsWith("http")) h else "https://$h"
        }
        // Bing 跳转：`u=a1<base64url>`——`a1` 是版本标记符，其后才是 base64("http(s)://...")，
        // 直接解码 `u=` 之后的部分（不得把 `a1` 拼回 base64，否则解码出乱码）。
        // base64url 不含 &/%，正则在完整 href 上取 u 参数并断言 a1 前缀（& 终止捕获）。
        val b64 = Regex("""[?&]u=a1([A-Za-z0-9+/=_-]+)""").find(href)?.groupValues?.get(1) ?: return ""
        return try {
            java.util.Base64.getUrlDecoder().decode(b64).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== 百度兜底（v1 批次7 Issue 2，中文实体救援） ====================

    /**
     * 百度搜索请求（v1 批次7）：仅作为「百度是中文专名实体的兜底来源」调用，非主路径。
     * 百度对"梧州一中"等能直接命中学校（AI 摘要/学校百科/政府/地图），弥补 Bing 的消歧缺陷。
     */
    private suspend fun fetchBaiduSearch(query: String): List<SearchItem> {
        val response: HttpResponse = httpClient.get(BAIDU_ENDPOINT) {
            url { parameters.append("wd", query) }
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
            header("Referer", BAIDU_REFERER)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        val body = response.bodyAsText(decodeCharset(response))
        val items = parseBaiduHtml(body)
        Log.i(
            LOG_TAG,
            "baidu query=${query.take(LOG_QUERY_MAX_LEN)} items=${items.size} first=${items.firstOrNull()?.title}"
        )
        return items
    }

    /**
     * 解析百度 HTML 有机结果（纯函数可测）。百度结果标题在 `<h3><a href="...">标题</a></h3>`，
     * href 多为 `http://www.baidu.com/link?url=<token>` 跳转（可被点击/抓取跟随定位真实页面）。
     * 摘要尽力提取 `c-abstract` 块，缺失时留空（标题已足以判相关）。
     *
     * @param body 百度 HTML 响应体
     * @return 标题/链接/摘要列表
     */
    internal fun parseBaiduHtml(body: String): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        val h3Regex = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
        for (m in h3Regex.findAll(body)) {
            val block = m.groupValues[1]
            val a = Regex("""<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .find(block)
            val href = a?.groupValues?.get(1) ?: continue
            val title = a?.groupValues?.get(2)?.let { clean(it) } ?: continue
            if (title.isEmpty()) continue
            // 摘要：取本 h3 之后固定窗口内的 c-abstract 块（Baidu 布局；尽力而为，缺失留空）
            val winStart = m.range.last
            val winEnd = (winStart + SNIPPET_SEARCH_WINDOW).coerceAtMost(body.length)
            val after = body.substring(winStart, winEnd)
            val snippet = Regex("""<div[^>]*class="[^"]*c-abstract[^"]*"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                .find(after)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            items.add(SearchItem(title = title, link = href, snippet = snippet))
        }
        return items
    }

    /**
     * 百度兜底入口：用 query 及其核心词依次查百度，取**首个相关**结果返回格式化文本；全不相关返回 null。
     * 受 [hasRequestBudget] 预算约束（只读路径已在 execute 中检查过，此处每请求前再自查）。
     */
    private suspend fun tryBaiduFallback(
        query: String,
        coreTerms: List<String>,
        maxResults: Int,
        elapsedMs: () -> Long
    ): String? {
        fun budget(): Boolean = Companion.hasRequestBudget(elapsedMs())
        suspend fun safeFetch(q: String): List<SearchItem> = try {
            fetchBaiduSearch(q)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "baidu search failed (${e::class.simpleName}), skip q=${q.take(LOG_QUERY_MAX_LEN)}")
            emptyList()
        }
        if (!budget()) return null
        val baiduPrimary = filterRelevantItems(safeFetch(query), coreTerms)
        if (baiduPrimary.isNotEmpty() && (coreTerms.isEmpty() || isRelevant(baiduPrimary, coreTerms))) {
            Log.i(LOG_TAG, "baidu fallback hit for query=${query.take(LOG_QUERY_MAX_LEN)} items=${baiduPrimary.size}")
            return formatSearchResult(baiduPrimary, maxResults)
        }
        for (term in coreTerms) {
            if (term == query) continue
            if (!budget()) break
            val items = filterRelevantItems(safeFetch(term), listOf(term))
            if (items.isNotEmpty() && isRelevant(items, listOf(term))) {
                Log.i(LOG_TAG, "baidu fallback hit for core=${term.take(LOG_QUERY_MAX_LEN)}")
                return formatSearchResult(items, maxResults)
            }
        }
        Log.w(LOG_TAG, "baidu fallback no hit for query=${query.take(LOG_QUERY_MAX_LEN)}")
        return null
    }

    /** 提取单个 XML 标签内容（忽略大小写，支持 CDATA）。 */
    private fun extractTag(block: String, tag: String): String? {
        val regex = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        val m = regex.find(block) ?: return null
        val raw = m.groupValues[1].trim()
        // 去除 CDATA 包裹（<![CDATA[ ... ]]>）
        val cdata = Regex("<!\\[CDATA\\[([\\s\\S]*?)\\]\\]>", RegexOption.IGNORE_CASE)
        val content = cdata.find(raw)?.groupValues?.get(1) ?: raw
        return content.trim()
    }

    /** HTML 实体解码 + 去标签 + 空白归一化 + 截断（纯函数，可测）。 */
    internal fun clean(raw: String): String {
        return decodeHtmlEntities(stripHtmlTags(raw))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(SNIPPET_MAX_LEN)
    }

    /** 去 HTML 标签（`<[^>]+>` → 空），保留文本内容。 */
    internal fun stripHtmlTags(raw: String): String = raw.replace(Regex("<[^>]+>"), "")

    /**
     * HTML 实体解码（常见实体 + 数字实体）。
     *
     * 覆盖 Bing RSS 摘要中常见实体：`&amp; &lt; &gt; &quot; &#39; &nbsp;` 及 `&#NNN;` 数字实体。
     * 未知实体保持原样（不引入完整 HTML 解析器依赖）。
     *
     * **S-1 修复**（guardrail TKN-P8-GUARDRAIL-001）：数字实体解码时：
     * - 合法可打印码点（32..0x10FFFF，不含 127）→ 解码为字符
     * - 控制字符（0..31、127）→ **移除**（不注入 NUL/控制字符污染回灌内容）
     * - 超范围码点（>0x10FFFF）→ 保留实体原文（避免非法码点）
     */
    internal fun decodeHtmlEntities(raw: String): String {
        var result = raw
        result = result.replace("&lt;", "<").replace("&gt;", ">")
        result = result.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        result = result.replace("&nbsp;", " ").replace("&amp;", "&")
        // 数字实体 &#NNN;（十进制）与 &#xHH;（十六进制）
        result = DECIMAL_ENTITY.replace(result) { match -> decodeNumericEntity(match.groupValues[1].toIntOrNull(), match.value) }
        result = HEX_ENTITY.replace(result) { match -> decodeNumericEntity(match.groupValues[1].toIntOrNull(16), match.value) }
        return result
    }

    /**
     * 将数字实体码点转为输出文本（S-1/R-1 语义）：
     * - 合法可打印码点（不含控制字符、孤立代理项）→ 对应字符
     * - 控制字符 / 非法值 → 空字符串（移除）
     * - 超范围码点 → 实体原文（保守保留）
     */
    private fun decodeNumericEntity(cp: Int?, rawEntity: String): String = when {
        cp == null -> rawEntity // 非数字（正则已保证，防御）
        cp < 32 || cp == 127 -> "" // 控制字符：移除，防 NUL/控制字符注入
        // R-1（guardrail TKN-P8-GUARDRAIL-002）：孤立代理项（0xD800..0xDFFF）无法单独成字，移除
        cp in 0xD800..0xDFFF -> ""
        cp > 0x10FFFF -> rawEntity // 超范围：保留原文
        else -> cp.toChar().toString()
    }

    companion object {
        /** 联网搜索工具命名空间前缀。 */
        const val NAMESPACE_PREFIX = "web_search__"

        /** 搜索工具名（`web_search__search`）。 */
        const val TOOL_SEARCH = "${NAMESPACE_PREFIX}search"

        /** Bing HTML 搜索端点（国内可访问、无需 Key，ADR-020 选型；v1 批次6 由 RSS 改 HTML 提升中文实体命中）。 */
        private const val BING_HTML_ENDPOINT = "https://cn.bing.com/search"

        /** HTML 请求 Referer（模拟搜索引擎→点进结果上下文，ADR-038 同思路）。 */
        private const val BING_HTML_REFERER = "https://cn.bing.com/"

        /**
         * v1 批次7（Issue 2，真机/浏览器实证）：Bing 中国对中文专名实体（如"梧州一中"/"梧州市第一中学"）
         * **查询消歧为城市**——连精确校名顶级返回的都是"梧州市(城市)百科/政府/旅游"，长期无法命中。
         * 百度对同类查询能直接命中学校（AI 摘要 + 学校百科/政府/地图/贴吧）。引入百度 HTML 作为
         * **兜底来源**：仅在 Bing 主查询+核心词重试全部不相关时触发，专门救援中文实体类查询。
         */
        private const val BAIDU_ENDPOINT = "https://www.baidu.com/s"
        private const val BAIDU_REFERER = "https://www.baidu.com/"

        /** 百度解析：抓取本 h3 之后窗口长度内的 c-abstract 作为摘要（防跨结果串味）。 */
        private const val SNIPPET_SEARCH_WINDOW = 1500

        /** 默认返回结果数。 */
        private const val DEFAULT_MAX_RESULTS = 5

        /** 最小结果数（防 LLM 传 0/负数）。 */
        private const val MIN_RESULTS = 1

        /**
         * 最大结果数（O5/PRD UXR8：8→10）。Bing RSS 单次恒返 10 条（实测 count/first
         * 参数被忽略），旧上限 8 白丢 2 条，补回到 10。多来源扩容由合并路径承担（见
         * [MERGE_MAX_RESULTS]）。
         */
        private const val MAX_RESULTS = 10

        /**
         * O5（PRD UXR8）：多查询合并触发阈值——LLM 请求 ≥8 条视为明确要求全面覆盖
         * 多来源，触发互补变体查询合并（默认 5 不触发，日常快查不多耗请求）。
         */
        private const val MERGE_TRIGGER_RESULTS = 8

        /**
         * O5：互补变体查询最大数。总串行请求预算 = 主查询 1 + 变体 2 = 3，
         * 对齐 [MAX_CORE_TERM_RETRIES] 的串行预算思路（防多请求放大延迟与限流风险）。
         */
        private const val MAX_MERGE_QUERIES = 2

        /**
         * O5：合并去重后总上限（防 token 溢出；PRD 验收要求 12-16 条）。
         */
        private const val MERGE_MAX_RESULTS = 16

        /**
         * LOW-01（guardrail TKN-UXR7R2-GUARDRAIL-001）：核心词降级重试的最大候选数。
         *
         * 候选核心词按出现顺序截断前 N 个，防止极端超长 query（如"昔涟 星穹铁道 崩坏
         * 角色 攻略 大全 视频 下载..."）产生过多候选，放大串行网络请求（每个候选一次
         * Bing 请求）。实践中候选 1 个（首实体）即可命中，N=3 覆盖多实体 query 且上限可控。
         */
        private const val MAX_CORE_TERM_RETRIES = 3

        /**
         * G-03（guardrail TKN-UXR8-B2-GUARDRAIL-001）：单次搜索请求超时上限。
         *
         * 与 PrismApplication 中 searchHttpClient 的 `HttpTimeout.requestTimeoutMillis = 10_000`
         * 对齐（耦合关系：client 侧超时变更时本常量须同步，预算检查以"下一请求最坏
         * 再耗 10s"为前提）。
         */
        internal const val SEARCH_REQUEST_TIMEOUT_MS = 10_000L

        /**
         * G-03：本工具执行的总时间预算。
         *
         * 与 [io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS]（30s，包裹本地工具
         * execute 的 `withTimeout`）对齐。耦合关系：SkillExecutor 默认超时变更时本常量
         * 须同步，否则预算检查失真（预算 > 实际超时会重新出现"子请求拖穿总超时"问题）。
         */
        internal const val TOTAL_TOOL_BUDGET_MS = 30_000L

        /**
         * G-03：预算安全缓冲（结果格式化 / 协程调度 / 日志等尾部开销）。
         *
         * 预算判据：`已耗时 + 单请求超时 ≤ 总预算 - 缓冲`，最坏路径（主 10s + 变体 10s）
         * 合计 20s + 尾部，远低于 30s，保证外层 withTimeout 不触发、已成功结果不丢弃。
         */
        internal const val BUDGET_SAFETY_MARGIN_MS = 3_000L

        /**
         * G-03：剩余预算是否足以再发起一次子请求（纯函数，可测）。
         *
         * 判据：`已耗时 + 单请求最坏超时 ≤ 总预算 - 安全缓冲`。
         * 不足时调用方应跳过新请求并保留已成功结果（见 execute 内两处 break）。
         */
        internal fun hasRequestBudget(elapsedMs: Long): Boolean =
            elapsedMs + SEARCH_REQUEST_TIMEOUT_MS <= TOTAL_TOOL_BUDGET_MS - BUDGET_SAFETY_MARGIN_MS

        /** 单条摘要最大长度（防 token 溢出 + 防超长污染回灌）。 */
        private const val SNIPPET_MAX_LEN = 200

        /**
         * LOW-03（guardrail TKN-UXR7R2-GUARDRAIL-001，CWE-532）：日志中 query 原文的最大长度。
         *
         * 搜索关键词可能包含用户 PII（姓名/手机号/私密问题），日志仅记录前 N 字符，
         * 降低 logcat 泄露敏感信息风险，同时保留 RCA 所需的 query 主体信息。
         */
        private const val LOG_QUERY_MAX_LEN = 120

        /** Bing 对无 UA 的自动化请求可能降级，设置常见浏览器 UA（对端只是返回更稳定的 HTML/RSS）。 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        private const val LOG_TAG = "WebSearchTool"

        /** 十进制数字实体（`&#NNN;`）。 */
        private val DECIMAL_ENTITY = Regex("&#(\\d+);")

        /** 十六进制数字实体（`&#xHH;`）。 */
        private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")

        /** O5：URL 归一化用 scheme/host 提取正则（忽略大小写；path 保留原大小写）。 */
        private val URL_SCHEME_HOST_REGEX = Regex("""^(https?)://([^/?#]+)(.*)$""", RegexOption.IGNORE_CASE)

        /** O5：常见跟踪参数（utm_* 由前缀匹配覆盖，此处为非 utm 系跟踪参数）。 */
        private val TRACKING_PARAM_KEYS = setOf("spm", "scm", "ref", "referrer")

        /**
         * 构建 `web_search__search` 工具定义（供 ConversationViewModel.buildTools 合并）。
         *
         * **JSON Schema**：query 必填（string）；maxResults 可选（integer 1..10）。
         * additionalProperties=false（严格参数，避免 LLM 传未知字段）。
         */
        fun buildToolDefinition(): ToolDefinition {
            val parameters = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "query" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("搜索关键词（简洁、明确，保持完整短语；若一次搜索未命中，不要反复用同义词重试，直接基于已有信息回答）")
                                )
                            ),
                            "maxResults" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("返回结果数量（1-10，默认 5）。需要全面调研多来源时传 8-10：自动合并互补查询，去重后最多 16 条来源"),
                                    "minimum" to JsonPrimitive(1),
                                    "maximum" to JsonPrimitive(10)
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("query"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_SEARCH,
                    description = "联网搜索互联网获取实时信息（Bing 零配置免费）。当用户询问最新新闻、" +
                        "实时数据、不确定的事实，或需要跨网页信息时调用。返回搜索结果列表" +
                        "（标题 + 链接 + 摘要），回答时按结果序号以 [N] 内联标注实际引用的来源。" +
                        "注意：结果为第三方网页摘要，未经验证，须甄别后引用。",
                    parameters = parameters
                )
            )
        }
    }
}
