# 11 — MCP 协议集成

## Model Context Protocol

MCP (Model Context Protocol) 是 Anthropic 提出的标准化协议，用于 AI 模型与外部工具/资源交互。OpenCode 同时作为 MCP 客户端和服务端。

## 传输方式

| 传输 | 类型 | 说明 |
|------|------|------|
| StdioClientTransport | 进程 | 通过标准输入输出通信 |
| SSEClientTransport | HTTP | Server-Sent Events |
| StreamableHTTPClientTransport | HTTP | 流式 HTTP |

## MCP 提供的能力

### Tools（工具）
外部 MCP Server 提供的工具会被转换为 AI SDK 的 Tool 格式，与内置工具统一管理。

### Resources（资源）
文件/URI 的访问抽象。

### Prompts（提示词）
可重用的提示词模板，支持参数化。

## MCP 与内置工具的关系

```
MCP 工具 ──转换──► AI SDK Tool 格式 ──合并──► 工具注册表
                                              ↑
内置工具 (read, bash, etc.) ─────────────────┘
```

当 MCP 工具可用性发生变化时，通过 Bus 发布 `mcp.tools.changed` 事件通知系统更新。

## OAuth 认证

MCP 内置了 OAuth Provider，支持需要认证的 MCP Server。

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/mcp/index.ts` | MCP 客户端管理 |
| `src/acp/` | Agent Control Protocol (类似但面向 Agent 编排) |
