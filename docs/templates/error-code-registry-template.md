# 错误码注册表（模板）

> 从本模板复制新建 `docs/error-code-registry.md`。依 CLAUDE.md 第十九节 19.2。
> 错误码格式：`ERR-<域>-<序号>`，全局登记。

## 错误码格式

```
ERR-<domain>-<NNN>
```

域（domain）示例：AUTH / MCP / RAG / MEM / SKILL / NET / STORE / UI

## 错误码清单

| 错误码 | 域 | 含义 | 触发条件 | 用户提示 | HTTP 状态（如适用） |
|---|---|---|---|---|---|
| ERR-MCP-001 | MCP | MCP Server 连接失败 | 远程 Server 不可达 | "无法连接到 MCP 服务，请检查地址" | — |
| ERR-MCP-002 | MCP | MCP Tool 调用超时 | Tool 执行超时 | "工具响应超时" | — |
| ERR-RAG-001 | RAG | 文档解析失败 | 不支持的格式或损坏 | "无法解析该文档" | — |
| ERR-RAG-002 | RAG | 嵌入模型加载失败 | ONNX 模型缺失或内存不足 | "知识库引擎不可用" | — |
| ERR-AUTH-001 | AUTH | API Key 无效 | 端点返回 401 | "API Key 无效，请检查配置" | 401 |
| ERR-NET-001 | NET | 端点不可达 | 网络故障 | "无法连接到 AI 服务" | — |

## 规则

1. 错误返回必须包含 `error_code` 和 `message`，不包含内部堆栈或路径
2. 错误码一经分配不可复用，废弃标注但保留
3. 新增错误码必须更新本表
