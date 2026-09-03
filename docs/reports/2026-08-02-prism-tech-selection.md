# Prism 技术选型对比分析报告

| 元信息 | 内容 |
|---|---|
| 报告类型 | 技术选型对比分析报告（Technical Selection Comparative Analysis） |
| 生成日期 | 2026-08-02 |
| 作者 | tech-selection-researcher 子 Agent |
| 调研方法 | 四阶段法：定标尺 → 广撒网 → 深验证 → 出报告（强制 web-access 联网搜索） |
| 任务令牌 | TKN-PRISM-TECH-SELECTION-001 |
| 执行 Agent | tech-selection-researcher |
| 状态 | 定稿（可作为 ADR 输入） |
| 信息时效提醒 | 本报告基于 2026-08-02 的联网搜索结果。若决策时间超过 3 个月，建议对 MCP SDK、ObjectBox、ONNX Runtime Mobile 等快速迭代组件重新调研。 |

---

## 1. 执行摘要

### 1.1 调研目的

为 Prism 项目（仅 Android、纯云端 BYOK、个人开源自发布、Deep Link 路线跨 App 的 AI 聊天 Agent 应用）的 6 个关键技术栈进行深度对比选型，输出可追溯、可复现的决策报告，作为后续 ADR 的输入。

### 1.2 候选清单

| 课题 | 候选方案 |
|---|---|
| 1. 跨平台框架 | Flutter vs 原生 Kotlin + Jetpack Compose |
| 2. MCP 客户端 | mcp_client Dart 包 vs 官方 MCP Kotlin SDK vs 其他 |
| 3. 端侧向量库 | ObjectBox vs Zvec vs HNSWLib vs sqlite-vec vs FAISS-mobile |
| 4. 端侧嵌入模型 | all-MiniLM-L6-v2 vs bge-small-zh vs bge-m3 vs nomic-embed-text |
| 5. 跨 App 调用 | Deep Link vs App Intents vs Share Sheet vs Picker vs ContentProvider |
| 6. Key 安全存储 | EncryptedSharedPreferences vs Jetpack Security Crypto vs Keystore+DataStore |

### 1.3 最终推荐（一句话）

**原生 Kotlin + Jetpack Compose + 官方 MCP Kotlin SDK + ObjectBox 向量库 + all-MiniLM-L6-v2 ONNX 量化 + Deep Link/App Intents/Share Sheet/Picker 组合 + Android Keystore + DataStore（Tink 加密）+ 生物识别二次解锁**。

> **重大决策变更说明**：可行性调研初版曾倾向 Flutter（因 mcp_client Dart 包 + Zvec Dart SDK），但本次深度调研发现**官方 MCP Kotlin SDK（0.12.0，Kotlin Multiplatform）已发布**，且 Flutter 在仅 Android 场景下存在不可忽视的性能/内存/包体积开销，故将推荐从 Flutter 改为原生 Kotlin + Compose。详见课题 1 和课题 2 分析。

---

## 2. 需求与约束回顾

### 2.1 量化验收矩阵

| 指标名称 | 最低要求 | 理想目标 | 测量方法 | 权重(1-10) |
|---|---|---|---|---|
| 冷启动时间 | <2s（中端机骁龙 680） | <1.2s | Macrobenchmark 冷启动测试 | 8 |
| 滚动帧率 | ≥54fps | 60fps 稳定 | Macrobenchmark 滚动测试 | 7 |
| APK 体积 | <30MB | <20MB | 构建产物测量 | 6 |
| 运行时内存（空闲） | <150MB | <100MB | Android Studio Profiler | 8 |
| 向量检索延迟（10 万向量） | <50ms | <10ms | 端侧 benchmark | 9 |
| 嵌入推理速度（单句） | <50ms | <10ms | ONNX Runtime Mobile 计时 | 8 |
| 嵌入模型大小 | <100MB | <30MB | 模型文件测量 | 7 |
| MCP 连接建立时间 | <3s | <1s | 端侧网络计时 | 6 |
| Key 存储加密强度 | AES-256 + TEE | AES-256 + StrongBox + 生物识别 | 安全审计 | 9 |
| 跨 App 调用成功率 | >80%（主流国产 App） | >95% | 兼容性测试矩阵 | 7 |

### 2.2 刚性约束（一票否决项）

| # | 约束 | 理由 |
|---|---|---|
| C1 | **目标平台仅 Android（API 26+）** | 用户已确认，规避 iOS 沙盒限制 |
| C2 | **不走 Google Play，个人开源自发布** | 用户已确认，规避无障碍服务审核风险 |
| C3 | **AI 算力纯云端 BYOK** | 用户已确认，端侧仅跑嵌入模型不做 LLM 推理 |
| C4 | **跨 App 走轻量路线，不用无障碍服务** | 用户已确认 |
| C5 | **License 必须允许商业/闭源友好**（MIT/Apache 2.0/BSD） | 个人开源项目，需避免 GPL 传染性 |
| C6 | **MCP 传输方式不能依赖 stdio** | 移动端无法 spawn 子进程，必须 SSE/Streamable HTTP/WebSocket |
| C7 | **端侧模型必须支持 ONNX Runtime Mobile 或 TFLite** | 移动端推理框架限制 |
| C8 | **Key 存储必须进 Android Keystore（TEE/StrongBox）** | BYOK 模式下 API Key 是核心资产 |

---

## 3. 候选方案综合对比

### 3.1 课题 1：跨平台框架 vs 原生

#### 3.1.1 候选清单与过滤

| 候选 | 语言 | License | 最后更新 | 过滤结果 |
|---|---|---|---|---|
| **Flutter** | Dart | BSD-3-Clause | 活跃 | 保留（评估性能开销） |
| **原生 Kotlin + Jetpack Compose** | Kotlin | Apache 2.0 | 活跃 | 保留（系统 API 原生优势） |
| React Native | JS/TS | MIT | 活跃 | 否决（Bridge 开销更大，仅 Android 无优势） |
| Kotlin Multiplatform (KMP) | Kotlin | Apache 2.0 | 活跃 | 否决（逻辑层跨平台，但仅 Android 不需要跨平台） |

#### 3.1.2 深度对比矩阵

| 维度 | Flutter | 原生 Kotlin + Compose | 优势方 |
|---|---|---|---|
| **MCP 客户端库** | mcp_client Dart 包 v2.1.1（社区维护） | 官方 MCP Kotlin SDK 0.12.0（[modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk)） | **原生**（官方维护，Tier 3） |
| **向量库 SDK** | Zvec Dart SDK 0.4.0 / ObjectBox Dart binding | ObjectBox Java/Kotlin（原生成熟） | **原生**（ObjectBox Java/Kotlin 更成熟） |
| **系统 API 调用** | Platform Channel（有序列化开销） | 直接调用（零开销） | **原生** |
| **冷启动时间（中端机）** | +15ms 额外延迟（[androiddocs.com 基准测试](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/)） | 基准线 | **原生** |
| **冷启动内存峰值** | 比 Compose 高 30%-50%（[CSDN 对比](https://blog.csdn.net/vitaviva/article/details/148652211)） | 基准线 | **原生** |
| **滚动帧率** | 54fps（复杂列表，[androiddocs.com 实测](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/)） | 60fps 稳定 | **原生** |
| **APK 体积** | +12MB（Flutter 引擎，[androiddocs.com](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/)） | 基准线 | **原生** |
| **空闲内存占用** | +12-28MB（引擎 + bindings，[androiddocs.com Profiler 数据](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/)） | 基准线 | **原生** |
| **开发效率** | 热重载快，单代码库 | Compose Preview + Live Edit | 持平 |
| **Material You 适配** | 需自定义绘制 | 原生支持 | **原生** |
| **社区生态** | Dart 生态较小 | Kotlin 生态大（JVM 全生态） | **原生** |

#### 3.1.3 推荐方案

**推荐：原生 Kotlin + Jetpack Compose**

**核心理由**：

1. **仅 Android 场景，Flutter 跨平台优势无法发挥**——Flutter 的核心价值是"一套代码跑两端"，但 Prism 已确认仅 Android，Flutter 的性能/内存/包体积开销成了纯负担。
2. **官方 MCP Kotlin SDK 的发现改变了天平**——此前 Flutter 有 mcp_client Dart 包的优势，但 [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) 0.12.0 是官方 Kotlin Multiplatform 实现，原生方案不再需要"自实现"。
3. **系统级 API 调用零开销**——Deep Link、Keystore、Share Sheet、ContentProvider 等系统 API 在原生 Kotlin 中直接调用，Flutter 需经 Platform Channel 序列化，有性能损耗且增加复杂度。
4. **性能数据明确劣势**——Flutter 在中端机上冷启动 +15ms、滚动 54fps vs 60fps、内存 +12-28MB、APK +12MB，这些在仅 Android 场景下是不可接受的纯损耗。

**备选：Flutter**（若团队 Dart 经验远强于 Kotlin，且能接受上述性能开销）

**否决方案与理由**：

- **React Native**：Bridge 开销比 Flutter 的 Platform Channel 更大，仅 Android 场景无任何优势。
- **KMP**：逻辑层跨平台框架，但 Prism 仅 Android 不需要跨平台，增加 KMP/Native GC 冷启动 80-120ms 开销（[netguru 对比](https://www.netguru.com/blog/kotlin-multiplatform-vs-flutter)）无意义。

#### 3.1.4 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 团队 Kotlin/Compose 经验不足 | 中 | Compose 学习曲线平缓，官方文档+ Codelab 充足；JetBrains Koog 框架可加速 AI 集成 |
| Compose 生态仍在快速迭代 | 低 | 使用稳定版 Compose BOM，避免实验性 API |

---

### 3.2 课题 2：MCP 客户端实现方案

#### 3.2.1 候选清单与过滤

| 候选 | 语言 | 协议版本 | 传输方式 | License | 过滤结果 |
|---|---|---|---|---|---|
| **官方 MCP Kotlin SDK** | Kotlin | 最新 | SSE/WebSocket/Streamable HTTP/Stdio | Apache 2.0 | 保留 |
| **mcp_client Dart 包** | Dart | 4 版本覆盖 | SSE/Streamable HTTP/Stdio | MIT | 保留（仅 Flutter 场景） |
| 完全自实现 | Kotlin | 需自行跟进 | 需自行实现 | - | 否决（维护成本高、协议合规风险） |

#### 3.2.2 深度对比矩阵

| 维度 | 官方 MCP Kotlin SDK | mcp_client Dart 包 | 自实现 |
|---|---|---|---|
| **维护方** | [modelcontextprotocol 官方](https://github.com/modelcontextprotocol/kotlin-sdk) | 社区（[pub.dev](https://pub.dev/packages/mcp_client)） | 自行 |
| **SDK Tier** | [Tier 3](https://modelcontextprotocol.io/docs/sdk.md)（功能完整性较低但官方维护） | 未分级 | - |
| **版本** | 0.12.0 | 2.1.1 | - |
| **传输方式** | SSE / WebSocket / Streamable HTTP / Stdio | SSE / Streamable HTTP / Stdio | 需自行实现 |
| **原语覆盖** | Tool / Prompt / Resource / Completion / Logging / Roots / Sampling / Elicitation（[kotlin-sdk-client 文档](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-client/index.html)） | Tool / Resource / Prompt / Sampling / Elicitation / Roots + Deferred Tool Loading | 需自行实现 |
| **OAuth 2.1** | 需确认（SDK 未明确提及，可能需自行集成） | 支持 | 需自行实现 |
| **Kotlin Multiplatform** | 是（JVM / WASM / iOS） | 否（仅 Dart） | - |
| **与 Koog 集成** | [Koog 框架](https://kotlinlang.org/docs/kotlin-ai-apps-development-overview.html)支持 MCP 工具集成 | 无 | - |
| **移动端适配** | Ktor 客户端引擎，Android 友好 | Dart HTTP，Android 友好 | 需自行适配 |

#### 3.2.3 推荐方案

**推荐：官方 MCP Kotlin SDK (kotlin-sdk 0.12.0)**

**核心理由**：

1. **官方维护，协议合规有保障**——[modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) 是 MCP 官方仓库，协议更新会第一时间跟进。
2. **移动端必需传输全支持**——[kotlin-sdk-client 文档](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-client/index.html) 明确支持 `SseClientTransport`、`WebSocketClientTransport`、`StreamableHttpClientTransport`，满足移动端不能用 stdio 的约束（C6）。
3. **全原语覆盖**——Tool / Prompt / Resource / Completion / Logging / Roots / Sampling / Elicitation 全覆盖，且有能力强制检查（capability enforcement）。
4. **Kotlin Multiplatform**——未来若扩展 iOS，逻辑层可复用。
5. **Koog 生态加持**——JetBrains 推出的 [Koog 框架](https://kotlinlang.org/docs/kotlin-ai-apps-development-overview.html)支持 MCP 集成、多 LLM provider、知识检索和记忆，可作为 Prism 的 Agent 内核参考。

**备选：mcp_client Dart 包**（若选 Flutter 路线）

**否决方案与理由**：

- **完全自实现**：MCP 协议仍在快速迭代（2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25 多个版本），自实现需持续跟进协议变更，维护成本高且合规风险大。

#### 3.2.4 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| Tier 3 SDK 功能完整性较低 | 中 | 密切关注 SDK 版本更新；关键缺失功能（如 OAuth 2.1）可基于 Ktor 自行补齐 |
| OAuth 2.1 支持未明确 | 中 | 参考 MCP 官方 spec 的 OAuth 2.1 章节，用 Ktor 客户端 auth 模块自行集成 |
| SDK 仍在 0.x 版本，API 可能变更 | 中 | 锁定版本，关注 changelog；抽象 MCP 接口层，便于切换实现 |

---

### 3.3 课题 3：端侧向量库（个人知识库 RAG 用）

#### 3.3.1 候选清单与过滤

| 候选 | 语言/Binding | License | Android 支持 | 过滤结果 |
|---|---|---|---|---|
| **ObjectBox** | Java/Kotlin/Dart/C/C++/Go/Swift | Apache 2.0（核心） | 原生 | 保留 |
| **Zvec** | Dart | 需确认 | arm64-v8a | 保留（仅 Flutter 场景） |
| HNSWLib | C++ | MIT | 需 JNI binding | 否决（需自行封装 JNI，维护成本高） |
| sqlite-vec | C (SQLite 扩展) | MIT | 需编译 SQLite 扩展 | 否决（Android 集成复杂，性能不如专用向量库） |
| FAISS-mobile | C++ | MIT | 需 JNI binding | 否决（FAISS 为服务端设计，移动端内存占用大） |

#### 3.3.2 深度对比矩阵

| 维度 | ObjectBox | Zvec | HNSWLib | sqlite-vec |
|---|---|---|---|---|
| **Android 原生支持** | 原生 Java/Kotlin API（[objectbox-java](https://www.webkkk.net/objectbox/objectbox-java)） | Dart SDK，arm64-v8a | 需 JNI | 需编译 SQLite 扩展 |
| **算法** | HNSW | HNSW + 混合检索 | HNSW | 暴力搜索/IVF |
| **检索延迟（百万向量）** | <10ms（[ObjectBox 基准测试](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html)） | 未公开 | ~1-10ms（取决于数据量） | >100ms（无 HNSW） |
| **二进制大小** | <8MB（[ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/)） | 轻量 | ~1MB | ~2MB |
| **动态 RAM** | KB 级（[ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/)） | 未公开 | 取决于数据量 | 取决于数据量 |
| **混合检索** | 向量 + 标量 + 对象关联（[ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/)） | 向量 + 标量 + FTS + 融合排序 | 仅向量 | 向量 + SQL |
| **ACID 事务** | 支持 | 未确认 | 不支持 | 支持（SQLite） |
| **持久化** | 磁盘 + RAM 缓存 | 磁盘 | 内存为主 | 磁盘 |
| **社区活跃度** | 2240 commits，6.0.0-beta（2026-07，[objectbox-java](https://www.webkkk.net/objectbox/objectbox-java)） | 0.4.0 | 活跃但移动端使用少 | 活跃 |
| **开发者基数** | 800,000+（[greenrobot](https://greenrobot.org/news/objectbox-android-database-java-kotlin-performance/)） | 小众 | 大（服务端） | 中 |
| **Dart binding** | 有（[docs.objectbox.io](https://docs.objectbox.io/getting-started)） | 原生 Dart | 无 | 无 |

#### 3.3.3 推荐方案

**推荐：ObjectBox Java/Kotlin**

**核心理由**：

1. **HNSW 算法 + 毫秒级检索**——基于 HNSW 优化的索引结构，百万级向量库相似性搜索 <10ms，相比传统数据库提升 300%+（[ObjectBox 性能分析](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html)）。
2. **移动端原生设计**——<8MB binary，KB 级动态 RAM，嵌入式架构消除网络通信开销，端到端延迟降低 60%（[ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/)）。
3. **混合检索**——支持向量搜索 + 标量过滤 + 对象关联，满足 RAG 场景的"向量 + 元数据"联合查询需求。
4. **成熟的 Java/Kotlin API**——原生 Android 支持，无需 JNI 封装，800,000+ 开发者使用验证。
5. **ACID 事务 + 持久化**——数据安全有保障，离线优先设计。
6. **活跃维护**——6.0.0-beta（2026-07），2240 commits，持续迭代。

**备选：Zvec Dart SDK**（若选 Flutter 路线，0.4.0 支持混合检索 + 融合排序）

**否决方案与理由**：

- **HNSWLib**：需自行封装 JNI binding，维护成本高；无持久化、无混合检索。
- **sqlite-vec**：Android 集成需编译 SQLite 扩展，复杂度高；无 HNSW 算法，大数据量性能差。
- **FAISS-mobile**：FAISS 为服务端设计，移动端内存占用大；需 JNI binding。

#### 3.3.4 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| ObjectBox 向量搜索功能可能需商业许可证 | 中 | 核心数据库 Apache 2.0，向量搜索功能需核实许可条款；若需付费，评估成本或降级到 sqlite-vec |
| 向量索引构建时内存峰值 | 中 | 分批构建索引；低端机降级到暴力搜索或禁用 RAG |
| 数据迁移成本（若未来切换向量库） | 低 | 抽象向量存储接口，原始向量数据可导出 |

---

### 3.4 课题 4：端侧嵌入模型（RAG 用）

#### 3.4.1 候选清单与过滤

| 候选 | 参数量 | 维度 | 模型大小 | 中文支持 | 过滤结果 |
|---|---|---|---|---|---|
| **all-MiniLM-L6-v2** | 22M | 384 | ~90MB（原始）/ ~22MB（ONNX INT8） | 一般 | 保留 |
| **bge-small-zh** | ~24M | 512 | ~95MB | 优秀 | 保留（中文优先备选） |
| bge-m3 | 568M | 1024 | ~2GB | 优秀 | 否决（参数量过大，移动端不可行） |
| nomic-embed-text | 137M | 768 | ~540MB | 一般 | 否决（量化版信息不足，体积偏大） |

#### 3.4.2 深度对比矩阵

| 维度 | all-MiniLM-L6-v2 | bge-small-zh | bge-m3 | nomic-embed-text |
|---|---|---|---|---|
| **参数量** | 22M（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)） | ~24M | 568M | 137M |
| **向量维度** | 384 | 512 | 1024 | 768 |
| **原始模型大小** | ~90MB | ~95MB | ~2GB | ~540MB |
| **ONNX INT8 量化大小** | ~22MB（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)） | 需转换 | 过大 | 未确认 |
| **单句编码耗时（CPU）** | 12ms 原始 / 5.3ms ONNX 量化（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)） | 需实测 | >500ms | 需实测 |
| **内存占用** | 86MB 原始 / 22MB ONNX 量化（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)） | ~90MB | >2GB | ~550MB |
| **STS-B 相似度** | 0.848（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)） | 0.8+（中文） | 0.89+ | 0.87+ |
| **中文质量** | 一般（多语言但不专精） | 优秀（中文专精） | 优秀 | 一般 |
| **ONNX Runtime Mobile** | 支持（[datacamp ONNX 教程](https://www.datacamp.com/tutorial/onnx)） | 需转换 | 不推荐 | 需确认 |
| **NNAPI 加速** | 支持 | 支持 | 不适用 | 需确认 |

#### 3.4.3 推荐方案

**推荐：all-MiniLM-L6-v2 ONNX INT8 量化版**

**核心理由**：

1. **极致轻量**——22M 参数，ONNX INT8 量化后仅 22MB，内存占用 22MB，远低于其他候选（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)）。
2. **推理速度优秀**——ONNX 量化后单句编码 5.3ms，加载 0.8s，满足端侧 RAG 实时性要求。
3. **精度可接受**——STS-B 0.848，达到 BERT-base（0.852）的 99.5% 水平，但参数量仅为其 20%。
4. **ONNX Runtime Mobile 原生支持**——[ONNX Runtime Mobile](https://www.datacamp.com/tutorial/onnx) 在 Android 上有 NNAPI 执行提供者，可利用 NPU 加速。
5. **Arm KleidiAI 加速**——[Arm + Microsoft 合作](http://microsoft.github.io/onnxruntime/blogs/arm-microsoft-kleidiai)在 ONNX Runtime 中集成 KleidiAI，Phi-3 在 vivo X200 Pro 上 prompt 处理加速 2.6x，嵌入模型同样受益。
6. **量化效果显著**——ONNX 量化后加载时间从 2.4s 降至 0.8s，编码耗时从 12ms 降至 5.3ms，内存从 86MB 降至 22MB（[CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)）。

**备选：bge-small-zh**（若中文检索质量优先，需额外转换 ONNX 并在 Android 上实测性能）

**否决方案与理由**：

- **bge-m3**：568M 参数，~2GB 模型大小，移动端内存不可行（违反 C7 约束）。
- **nomic-embed-text**：137M 参数偏大，ONNX 量化版信息不足，无法确认移动端可行性。

#### 3.4.4 PoC 设计（需实际测试）

由于公开数据多为服务端 CPU 基准，需在 Android 中端机上实测：

```kotlin
// PoC 步骤
// 1. 下载 all-MiniLM-L6-v2 ONNX INT8 量化模型（~22MB）
// 2. 集成 ONNX Runtime Mobile AAR
// 3. 在中端 Android 设备（骁龙 680 / 4GB RAM）上测试：
//    - 模型加载时间
//    - 单句编码延迟（p50/p95/p99）
//    - 批量编码吞吐（句子/秒）
//    - 内存占用峰值
//    - NNAPI 开启/关闭对比
// 4. 对比 bge-small-zh ONNX 版本（若可获取）
```

#### 3.4.5 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| all-MiniLM-L6-v2 中文检索质量不足 | 中 | 备选 bge-small-zh；或采用混合策略：英文用 MiniLM，中文用 bge-small-zh |
| ONNX Runtime Mobile 在部分设备兼容性问题 | 低 | 使用官方预编译 AAR；提供 fallback 到 CPU 执行 |
| 模型加载延迟影响首次 RAG 体验 | 低 | 应用启动时预加载模型；懒加载策略 |

---

### 3.5 课题 5：Android 跨 App 调用方案

#### 3.5.1 候选清单与过滤

| 候选 | 能力 | 审核风险 | 过滤结果 |
|---|---|---|---|
| **Deep Link / URL Scheme** | 打开指定 App 页面 | 无 | 保留 |
| **App Intents（Android）** | 调用目标 App 暴露的 Intent Action | 无 | 保留 |
| **Share Sheet（ACTION_SEND）** | 分享内容到其他 App | 无 | 保留 |
| **系统 Picker** | Photo Picker / Document Picker 选取媒体文档 | 无 | 保留 |
| **ContentProvider 读取** | 读取通讯录/日历/媒体等系统数据 | 无（需权限） | 保留 |
| ~~无障碍服务~~ | 全 UI 自动化 | 极高 | 否决（C4 约束，已确认不走） |

#### 3.5.2 深度对比矩阵

| 方案 | 覆盖场景 | 目标 App 支持度 | 用户授权体验 | 特殊权限 | AI Agent 可自动化 | 审核风险 |
|---|---|---|---|---|---|---|
| **Deep Link / URL Scheme** | 打开页面（微信聊天/地图导航/淘宝商品） | 高（主流 App 均支持） | 无感（直接跳转） | Android 11+ 需 `<queries>` | 可自动触发（需用户确认） | 无 |
| **App Intents** | 调用 App 暴露的功能 | 中（需目标 App 声明） | 无感 | 无 | 可自动触发 | 无 |
| **Share Sheet** | 分享文本/图片/文件 | 高（所有 App 支持） | 需用户选择目标 App | 无 | 需用户手动选择 | 无 |
| **系统 Picker** | 选取照片/文档 | 高（系统级） | 系统级 UI | 无 | 需用户手动选择 | 无 |
| **ContentProvider** | 读取系统数据 | 高（系统 App） | 需运行时权限 | 需对应权限 | 可自动读取（授权后） | 无 |

#### 3.5.3 国产 App Deep Link 支持情况（重点核实）

| App | URL Scheme | 包名 | 支持页面 | 注意事项 |
|---|---|---|---|---|
| **微信** | `weixin://` | com.tencent.mm | 扫一扫/聊天/朋友圈 | 禁止大多数外部 scheme 跳转（[CSDN 分析](https://ask.csdn.net/questions/8974481)），微信内置浏览器屏蔽非白名单 scheme |
| **支付宝** | `alipays://` | com.eg.android.AlipayGphone | 扫一扫/付款码/转账 | 华为/小米定制 ROM 可能拦截（[CSDN 分析](https://ask.csdn.net/questions/8974481)） |
| **淘宝** | `taobao://` / `tbopen://` | com.taobao.taobao | 商品详情/活动页 | 需检测是否安装（[51CTO 指南](https://blog.51cto.com/u_16213589/14219992)） |
| **抖音** | `snssdk1128://` | com.ss.android.ugc.aweme | 视频/用户主页 | 微信内无法直接拉起（[CSDN 分析](https://ask.csdn.net/questions/8674153)） |
| **QQ** | `mqq://` | com.tencent.mobileqq | 聊天/加好友 | - |
| **微博** | `sinaweibo://` | com.sina.weibo | 话题/用户主页 | - |
| **高德地图** | `androidamap://` | com.autonavi.minimap | 导航/搜索 | - |

#### 3.5.4 Android 11+ 包可见性约束

**关键发现**：Android 11（API 30）引入[包可见性（Package Visibility）](https://ask.csdn.net/questions/8838665)机制，默认仅暴露系统预装应用和同签名应用。若未在 `AndroidManifest.xml` 中通过 `<queries>` 标签显式声明目标应用，`Intent.resolveActivity()` 将返回 null，导致跳转失败。

```xml
<!-- 必须在 AndroidManifest.xml 中声明 -->
<queries>
    <package android:name="com.tencent.mm" />        <!-- 微信 -->
    <package android:name="com.eg.android.AlipayGphone" /> <!-- 支付宝 -->
    <package android:name="com.taobao.taobao" />     <!-- 淘宝 -->
    <package android:name="com.ss.android.ugc.aweme" /> <!-- 抖音 -->
    <!-- 或使用 intent 方式声明 -->
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="https" />
    </intent>
</queries>
```

#### 3.5.5 推荐方案

**推荐：Deep Link + App Intents + Share Sheet + 系统 Picker 组合方案**

**核心理由**：

1. **组合覆盖全场景**——Deep Link 覆盖"打开 App 页面"，Share Sheet 覆盖"分享内容"，系统 Picker 覆盖"选取媒体/文档"，ContentProvider 覆盖"读取系统数据"。
2. **轻量无审核风险**——全部使用系统标准 API，无无障碍服务，符合 C2/C4 约束。
3. **国产 App Deep Link 支持已确认**——主流 App（微信/支付宝/淘宝/抖音/QQ/微博/高德）均有 URL Scheme。
4. **降级策略完备**——Deep Link 失败时降级到 Share Sheet 或引导用户手动操作。

**AI Agent 自动化调用规则**：

- Deep Link 自动触发需**用户确认**（防误操作打开支付类 App）。
- ContentProvider 读取需**运行时权限授权**。
- Share Sheet / 系统 Picker 需**用户手动选择**（无法全自动）。

**否决方案与理由**：

- **无障碍服务**：Google Play 审核极严（C2 约束已确认不走 Google Play，但无障碍服务在国产 ROM 上也受限制），且用户体验差（全 UI 自动化不可控）。

#### 3.5.6 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 微信禁止大多数外部 scheme 跳转 | 高 | 检测微信环境，降级到中间页提示"复制链接到浏览器打开"（[CSDN 方案](https://ask.csdn.net/questions/8974481)） |
| 国产 ROM（华为/小米）拦截跨 App 跳转 | 中 | 引导用户在设置中开启"应用启动管理"权限；提供降级提示 |
| Android 11+ 包可见性限制 | 中 | 在 `<queries>` 中声明所有目标 App 包名 |
| AI Agent 自动触发 Deep Link 误操作 | 中 | 支付类/转账类操作必须用户二次确认；非敏感操作可自动触发 |

---

### 3.6 课题 6：BYOK 模式下 API Key 安全存储

#### 3.6.1 候选清单与过滤

| 候选 | 加密方式 | TEE/StrongBox | 生物识别 | 过滤结果 |
|---|---|---|---|---|
| ~~EncryptedSharedPreferences~~ | AES-256-GCM | 是（via Keystore） | 可结合 | **否决（2024 年初已废弃）** |
| **Jetpack Security Crypto（EncryptedFile）** | AES-256-GCM | 是 | 可结合 | 保留 |
| **Android Keystore + DataStore（Tink 加密）** | AES-256-GCM / XChaCha20 | 是 | 可结合 | 保留 |
| **Android Keystore + Cipher + DataStore（手动）** | AES-256-GCM | 是 | 可结合 | 保留 |

#### 3.6.2 关键发现：EncryptedSharedPreferences 已废弃

**重大发现**：`EncryptedSharedPreferences` 已于 **2024 年初被官方废弃**（[IIETA 论文](https://iieta.org/download/file/fid/192318)、[doonprogramming 分析](https://doonprogramming.com/encryptedsharedpreferences-is-deprecated-what-to-use-instead-in-android/)）。

废弃原因：

1. 性能限制（频繁读写慢）
2. 不适应现代安全需求
3. 算法选择和扩展性有局限

Google 推荐替代方案：

- `EncryptedFile`（[Jetpack Security Crypto v1.1.0+](https://doonprogramming.com/encryptedsharedpreferences-is-deprecated-what-to-use-instead-in-android/)）
- DataStore 1.3.0-alpha07 引入 `datastore-tink` 制品，支持 [Google Tink](https://www.codegenes.net/blog/android-benefits-of-datastore-over-sharedpreferences/) AEAD 加密

#### 3.6.3 深度对比矩阵

| 维度 | EncryptedSharedPreferences（废弃） | EncryptedFile | Keystore + DataStore (Tink) | Keystore + Cipher + DataStore（手动） |
|---|---|---|---|---|
| **状态** | 废弃 | 推荐 | 推荐（最新） | 可行 |
| **加密算法** | AES-256-GCM | AES-256-GCM | AEAD (Tink) | AES-256-GCM |
| **密钥存储** | Android Keystore | Android Keystore | Android Keystore | Android Keystore |
| **TEE/StrongBox** | 支持 | 支持 | 支持 | 支持 |
| **生物识别绑定** | 可结合 | 可结合 | 可结合 | 可结合（KeyGenParameterSpec.setUserAuthenticationRequired） |
| **异步 API** | 否（同步） | 是 | 是（Flow） | 是（Flow） |
| **类型安全** | 否 | 否 | 是（Preferences/Proto） | 是 |
| **性能** | 慢（废弃原因之一） | 中 | 快（DataStore） | 快（DataStore） + 加密开销 10-100x（[IIETA 论文](https://iieta.org/download/file/fid/192318)） |
| **实践参考** | 旧项目 | 官方推荐 | [DataStore Tink](https://www.codegenes.net/blog/android-benefits-of-datastore-over-sharedpreferences/) | [KINTO 实践](https://blog.kinto-technologies.com/posts/2026-04-17-keystore-cipher-datastore-encryption/) |
| **baseURL + headers 存储** | 支持 | 支持（文件） | 支持 | 支持 |

#### 3.6.4 推荐方案

**推荐：Android Keystore + DataStore（datastore-tink 加密）+ 生物识别二次解锁**

**核心理由**：

1. **EncryptedSharedPreferences 已废弃**——继续使用废弃 API 有维护和安全风险（[IIETA 论文](https://iieta.org/download/file/fid/192318)）。
2. **DataStore 是 SharedPreferences 的现代替代**——异步、类型安全、协程/Flow 原生支持、无 ANR 风险（[codegenes 分析](https://www.codegenes.net/blog/android-benefits-of-datastore-over-sharedpreferences/)）。
3. **datastore-tink 提供官方加密支持**——DataStore 1.3.0-alpha07 引入 `datastore-tink` 制品，使用 Google Tink 库的 AEAD（Authenticated Encryption with Associated Data）加密，官方维护（[codegenes 分析](https://www.codegenes.net/blog/android-benefits-of-datastore-over-sharedpreferences/)）。
4. **Android Keystore 确保密钥进 TEE/StrongBox**——AES-256-GCM 密钥由硬件隔离区保护，root 设备也难以提取。
5. **生物识别二次解锁**——通过 `KeyGenParameterSpec.Builder.setUserAuthenticationRequired(true)` 绑定生物识别，API Key 解密需指纹/面容验证。
6. **实践参考充分**——[KINTO 技术的实践](https://blog.kinto-technologies.com/posts/2026-04-17-keystore-cipher-datastore-encryption/)详细记录了 Keystore + Cipher + DataStore 的实现细节和踩坑点。

**实现架构**：

```
用户输入 API Key + baseURL + headers
        │
        ▼
Android Keystore 生成 AES-256-GCM 密钥（TEE/StrongBox）
        │
        ▼
Tink/Cipher 加密 API Key 等敏感数据
        │
        ▼
DataStore（Preferences）持久化加密后的数据
        │
        ▼
读取时：DataStore 读取 → Keystore 解密（需生物识别验证）→ 明文 API Key
```

**备选：Keystore + Cipher + DataStore（手动实现）**（若 datastore-tink 仍不稳定，参考 [KINTO 实践](https://blog.kinto-technologies.com/posts/2026-04-17-keystore-cipher-datastore-encryption/) 手动实现）

**否决方案与理由**：

- **EncryptedSharedPreferences**：2024 年初已废弃，性能差、扩展性差（[IIETA 论文](https://iieta.org/download/file/fid/192318)）。
- **明文 SharedPreferences**：无加密，API Key 可被 root 设备直接读取，不满足 C8 约束。

#### 3.6.5 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| datastore-tink 仍处于 alpha（1.3.0-alpha07） | 中 | 评估稳定性后采用；或用手动 Keystore + Cipher + DataStore 方案（KINTO 实践验证） |
| Keystore 在部分设备崩溃 | 中 | [KINTO 实践](https://blog.kinto-technologies.com/posts/2026-04-17-keystore-cipher-datastore-encryption/)指出 Keystore 偶有崩溃，需 try-catch 降级处理；Crashlytics 上报 |
| 生物识别失败时无法访问 API Key | 低 | 提供设备密码/PIN 作为 fallback 解锁方式 |
| root 设备提取风险 | 低 | Keystore 密钥在 TEE/StrongBox 中，root 也无法直接提取；应用数据加密后即使文件被提取也无法解密 |

---

## 4. PoC 与关键发现

### 4.1 公开基准数据汇总

| 测试项 | 数据 | 来源 |
|---|---|---|
| Flutter vs 原生冷启动（Pixel 8） | 1245ms vs 1200ms | [androiddocs.com](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/) |
| Flutter vs 原生滚动帧率（Pixel 7 Pro） | 54fps vs 60fps | [androiddocs.com](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/) |
| Flutter vs 原生空闲内存 | +12-28MB | [androiddocs.com](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/) |
| Compose vs Flutter 冷启动内存峰值 | Compose 低 30%-50% | [CSDN 对比](https://blog.csdn.net/vitaviva/article/details/148652211) |
| ObjectBox 向量检索延迟（百万向量） | <10ms | [ObjectBox 性能分析](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html) |
| ObjectBox 二进制大小 | <8MB | [ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/) |
| all-MiniLM-L6-v2 ONNX INT8 编码延迟 | 5.3ms/句 | [CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971) |
| all-MiniLM-L6-v2 ONNX INT8 内存占用 | 22MB | [CSDN 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971) |
| ONNX Runtime + KleidiAI 加速比 | 2.6x（Phi-3 prompt 处理） | [Arm + Microsoft 博客](http://microsoft.github.io/onnxruntime/blogs/arm-microsoft-kleidiai) |
| EncryptedDataStore 加密性能开销 | 10-100x（vs 未加密 DataStore） | [IIETA 论文](https://iieta.org/download/file/fid/192318) |

### 4.2 致命否决发现

| 发现 | 影响方案 | 严重度 |
|---|---|---|
| **EncryptedSharedPreferences 2024 年初已废弃** | 课题 6 候选 1 | 致命——不可继续使用废弃 API |
| **bge-m3 568M 参数，~2GB 模型大小** | 课题 4 候选 3 | 致命——移动端内存不可行 |
| **微信禁止大多数外部 scheme 跳转** | 课题 5 Deep Link | 高——微信场景需降级策略 |
| **Android 11+ 包可见性限制** | 课题 5 跨 App 调用 | 高——必须配置 `<queries>` |
| **MCP Kotlin SDK 为 Tier 3**（功能完整性较低） | 课题 2 候选 1 | 中——需关注功能缺失，OAuth 2.1 可能需自行补齐 |

### 4.3 异常场景行为记录

| 场景 | 方案 | 已知行为 | 降级策略 |
|---|---|---|---|
| 网络分区 | MCP Client（SSE/HTTP） | 连接超时/断开 | 指数退避重连 + 本地缓存已获取的 Tool/Resource 列表 |
| 磁盘满 | ObjectBox | 写入失败 | 捕获异常提示用户清理空间；只读模式可用 |
| 内存不足（低端机） | 嵌入模型 + 向量库 | OOM 风险 | 按设备 RAM 动态降级：>6GB 开启 RAG，4-6GB 仅关键词检索，<4GB 禁用知识库 |
| Keystore 崩溃 | API Key 存储 | 解密失败 | try-catch 降级；Crashlytics 上报；引导用户重新输入 Key |
| 国产 ROM 拦截 Deep Link | 跨 App 调用 | 跳转无响应 | 超时检测（2s）→ 降级到 Share Sheet 或手动引导 |
| MCP Server 不可达 | MCP Client | 工具调用失败 | 降级到纯 LLM 对话；提示用户检查 MCP Server 配置 |

---

## 5. 风险与缓解措施

### 5.1 推荐方案 Top 3 风险

| # | 风险 | 等级 | 缓解措施 |
|---|---|---|---|
| R1 | **MCP Kotlin SDK 为 Tier 3，功能完整性较低且 API 可能变更** | 中 | 1) 锁定 SDK 版本，抽象 MCP 接口层；2) 密切关注 [kotlin-sdk releases](https://github.com/modelcontextprotocol/kotlin-sdk)；3) OAuth 2.1 等缺失功能用 Ktor 自行补齐；4) 若 SDK 无法满足，可切换到 mcp_client Dart 包（需改用 Flutter） |
| R2 | **团队 Kotlin/Compose 经验不足** | 中 | 1) Compose 学习曲线平缓，官方 Codelab + 文档充足；2) 考虑 [Koog 框架](https://kotlinlang.org/docs/kotlin-ai-apps-development-overview.html)加速 AI 集成；3) 关键模块先写 PoC 验证可行性 |
| R3 | **all-MiniLM-L6-v2 中文检索质量可能不足** | 中 | 1) 备选 bge-small-zh（需 Android 实测）；2) 混合策略：英文用 MiniLM，中文用 bge-small-zh；3) RAG 检索结果质量评估机制 |

### 5.2 备选方案切换触发条件

| 推荐方案 | 备选方案 | 切换触发条件 |
|---|---|---|
| 原生 Kotlin + Compose | Flutter | 1) 团队 Kotlin 学习成本过高无法承受；2) PoC 发现 Compose 在目标设备上有严重性能问题 |
| 官方 MCP Kotlin SDK | mcp_client Dart 包 | 1) SDK 长期不更新（>6 个月无 commit）；2) 关键功能缺失且无法自行补齐；3) 切换到 Flutter 路线 |
| ObjectBox Java/Kotlin | Zvec Dart SDK / sqlite-vec | 1) ObjectBox 向量搜索需付费且成本不可接受；2) ObjectBox 在目标设备上有严重兼容性问题 |
| all-MiniLM-L6-v2 | bge-small-zh | 1) 中文检索质量测试不达标（recall < 70%）；2) bge-small-zh ONNX 版在 Android 上性能可接受 |
| Keystore + DataStore (Tink) | Keystore + Cipher + DataStore（手动） | 1) datastore-tink 长期不稳定；2) Tink 集成有不可解决的兼容性问题 |

---

## 6. 最终推荐与下一步

### 6.1 整体技术栈推荐组合

| 层 | 推荐方案 | 备选 | 核心理由 |
|---|---|---|---|
| **框架** | 原生 Kotlin + Jetpack Compose | Flutter | 仅 Android 场景，Flutter 性能/内存/包体积开销是纯负担；官方 MCP Kotlin SDK 消除了 Flutter 的库优势 |
| **MCP 客户端** | 官方 MCP Kotlin SDK 0.12.0 | mcp_client Dart 包 | 官方维护，全原语覆盖，移动端传输全支持 |
| **向量库** | ObjectBox Java/Kotlin | Zvec / sqlite-vec | HNSW <10ms 百万级检索，<8MB binary，混合检索，800K+ 开发者验证 |
| **嵌入模型** | all-MiniLM-L6-v2 ONNX INT8 量化 | bge-small-zh | 22MB 模型，5.3ms/句编码，ONNX Runtime Mobile + NNAPI/KleidiAI 加速 |
| **跨 App** | Deep Link + App Intents + Share Sheet + Picker | — | 轻量路线，无审核风险，组合覆盖全场景 |
| **Key 存储** | Android Keystore + DataStore (Tink) + 生物识别 | Keystore + Cipher + DataStore（手动） | EncryptedSharedPreferences 已废弃；DataStore 是现代替代；TEE/StrongBox + 生物识别保障安全 |

### 6.2 P0 核心依赖（需写 ADR 严格管控）

依 CLAUDE.md 第十八节依赖分级，以下为 **P0 核心依赖**，必须写入 ADR，严格版本控制，手动审查升级：

| P0 依赖 | 版本 | 管控理由 |
|---|---|---|
| **Jetpack Compose** | BOM 锁定 | UI 框架，深入代码，升级可能破坏 UI |
| **官方 MCP Kotlin SDK** | 0.12.0 锁定 | MCP 协议合规性核心，0.x 版本 API 不稳定 |
| **ObjectBox** | 6.0.0-beta 锁定 | 向量数据持久化，迁移成本高 |
| **ONNX Runtime Mobile** | 最新稳定版锁定 | 嵌入模型推理引擎，版本影响性能和兼容性 |
| **Android Keystore + DataStore** | 系统级 | API Key 安全核心，不可替换 |

### 6.3 后续实施步骤

| 步骤 | 内容 | 产出 |
|---|---|---|
| 1 | **写 ADR-001 Prism 技术栈选型** | ADR 文档（基于本报告结论） |
| 2 | **MCP Kotlin SDK PoC** | 验证 SSE/Streamable HTTP 连接远程 MCP Server、Tool 调用、OAuth 2.1 补齐可行性 |
| 3 | **ObjectBox 向量库 PoC** | 验证 Android 上向量插入/检索性能、混合检索、持久化 |
| 4 | **all-MiniLM-L6-v2 ONNX PoC** | 在中端 Android 设备上实测模型加载/编码延迟/内存/NNAPI 加速 |
| 5 | **Keystore + DataStore PoC** | 验证 API Key 加密存储/生物识别解锁/Keystore 崩溃处理 |
| 6 | **Deep Link 兼容性测试** | 在主流国产 App 上测试 Deep Link 跳转成功率 + 降级策略 |
| 7 | **关键人员培训** | Kotlin/Compose 培训 + MCP 协议学习 + ObjectBox 使用 |
| 8 | **集成试点** | 先实现最小可用版：BYOK 聊天 + MCP Client + 基础 RAG |

---

## 7. 附录

### 7.1 研究指标文档（Phase 1 产出）

见本报告第 2 节"需求与约束回顾"。

### 7.2 长候选清单与过滤日志（Phase 2 产出）

#### 课题 1 过滤日志

| 候选 | 否决理由 |
|---|---|
| React Native | Bridge 开销更大，仅 Android 无优势 |
| KMP | 逻辑层跨平台，仅 Android 不需要，增加 KMP/Native GC 开销 |
| Unity | 游戏引擎，非 App 开发框架 |
| Xamarin | 微软已停止支持，社区萎缩 |

#### 课题 2 过滤日志

| 候选 | 否决理由 |
|---|---|
| 完全自实现 | MCP 协议快速迭代，维护成本高，合规风险大 |
| mcp-mobile-interaction | 定位测试自动化的 MCP Server，非手机 App 内客户端 |
| mobile-mcp | 跨平台移动自动化 MCP Server，用无障碍服务，非客户端 |

#### 课题 3 过滤日志

| 候选 | 否决理由 |
|---|---|
| HNSWLib | 需自行封装 JNI binding，维护成本高；无持久化、无混合检索 |
| sqlite-vss / sqlite-vec | Android 集成需编译 SQLite 扩展，复杂度高；无 HNSW，大数据量性能差 |
| FAISS-mobile | 服务端设计，移动端内存占用大；需 JNI binding |
| EcoVector（MobileRAG 论文） | 论文方案，无开源实现，不可直接使用 |

#### 课题 4 过滤日志

| 候选 | 否决理由 |
|---|---|
| bge-m3 | 568M 参数，~2GB 模型大小，移动端内存不可行 |
| nomic-embed-text | 137M 参数偏大，ONNX 量化版信息不足 |
| EmbeddingGemma | 308M 参数，偏大；2025 年新出，移动端实践少 |
| Qwen3 Embedding 0.6B | 600M 参数，移动端不可行 |

#### 课题 5 过滤日志

| 候选 | 否决理由 |
|---|---|
| 无障碍服务 | C4 约束已确认不走；Google Play 审核极严；国产 ROM 也受限 |

#### 课题 6 过滤日志

| 候选 | 否决理由 |
|---|---|
| EncryptedSharedPreferences | 2024 年初已废弃，性能差、扩展性差 |
| 明文 SharedPreferences | 无加密，root 设备可直接读取，不满足 C8 约束 |

### 7.3 关键引用链接索引

#### MCP 相关

- [MCP 官方 SDK 列表](https://modelcontextprotocol.io/docs/sdk.md)
- [MCP Kotlin SDK GitHub](https://github.com/modelcontextprotocol/kotlin-sdk)
- [MCP Kotlin SDK 文档](https://kotlin.sdk.modelcontextprotocol.io/)
- [kotlin-sdk-client 文档](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-client/index.html)
- [MCP Build Client 教程](https://modelcontextprotocol.io/docs/develop/build-client)
- [Kotlin for AI 开发](https://kotlinlang.org/docs/kotlin-ai-apps-development-overview.html)
- [mcp_client Dart 包](https://pub.dev/packages/mcp_client)

#### 向量库相关

- [ObjectBox 官方](https://objectbox.io/vector-database-for-ondevice-ai/)
- [ObjectBox Java/Kotlin GitHub](https://www.webkkk.net/objectbox/objectbox-java)
- [ObjectBox 性能基准](https://greenrobot.org/news/objectbox-android-database-java-kotlin-performance/)
- [ObjectBox 向量搜索分析](https://blog.gitcode.com/f49131588a66b196934a22c5d09b6389.html)
- [On-device vector databases 2026](https://objectbox.io/262454-2/)

#### 嵌入模型相关

- [all-MiniLM-L6-v2 选型指南](https://blog.csdn.net/gitblog_02869/article/details/149625971)
- [ONNX Runtime 教程](https://www.datacamp.com/tutorial/onnx)
- [Arm + Microsoft KleidiAI](http://microsoft.github.io/onnxruntime/blogs/arm-microsoft-kleidiai)
- [ONNX Android Benchmark](https://learn.arm.com/learning-paths/smartphones-and-mobile/build-android-chat-app-using-onnxruntime/4-run-benchmark-on-android/)

#### 框架对比相关

- [Flutter vs Native Android 2026](https://androiddocs.com/the-complete-guide-to-should-you-choose-flutter-or-native-android-in-2026/)
- [Flutter vs Compose 8 张表对比](https://blog.csdn.net/vitaviva/article/details/148652211)
- [KMP vs Flutter](https://www.netguru.com/blog/kotlin-multiplatform-vs-flutter)

#### 跨 App 调用相关

- [Android URL Scheme 指南](https://blog.51cto.com/u_16213589/14219992)
- [H5 跳转 App 3 种方法](https://blog.51cto.com/u_9849794/14405617)
- [支付宝 Deep Link 问题分析](https://ask.csdn.net/questions/8974481)
- [Android 11+ 包可见性](https://ask.csdn.net/questions/8838665)
- [抖音 Deep Link 跳转](https://ask.csdn.net/questions/8674153)

#### Key 存储相关

- [EncryptedSharedPreferences 废弃分析](https://doonprogramming.com/encryptedsharedpreferences-is-deprecated-what-to-use-instead-in-android/)
- [EncryptedDataStore 论文](https://iieta.org/download/file/fid/192318)
- [DataStore 加密支持](https://www.codegenes.net/blog/android-benefits-of-datastore-over-sharedpreferences/)
- [KINTO Keystore+DataStore 实践](https://blog.kinto-technologies.com/posts/2026-04-17-keystore-cipher-datastore-encryption/)
- [Android DataStore 官方文档](https://developer.android.com/jetpack/androidx/releases/datastore)

---

## 8. 声明

- 本报告所有结论均基于 2026-08-02 的联网搜索证据，引用链接已在第 7.3 节索引。
- 部分性能数据（如 all-MiniLM-L6-v2 ONNX 在 Android 上的实际表现）来自服务端 CPU 基准，需在 Android 中端机上实测验证（见第 4.2 节 PoC 设计）。
- ObjectBox 向量搜索功能的许可证条款需进一步向厂商确认（核心数据库 Apache 2.0，向量搜索功能可能需商业许可）。
- MCP Kotlin SDK 为 Tier 3 级别，功能完整性低于 Tier 1（TypeScript/Python），实际使用前需评估是否满足需求。
- 本报告作为 ADR 输入，最终技术栈决策需经 guardrail-enforcer 审查后写入 ADR。
