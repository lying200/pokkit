# 14 — Server 与 API

## 服务架构

OpenCode 的 Server 基于 Hono 框架，提供 HTTP 和 WebSocket 接口。

### 核心特性

- **REST API** — 标准 HTTP 端点
- **WebSocket** — 实时流式通信
- **OpenAPI** — 自动生成 API 文档
- **Basic Auth** — 基于密码的认证 (`OPENCODE_SERVER_PASSWORD`)
- **CORS** — 跨域支持

## Client/Server 模式

```
┌─────────┐     HTTP/WS      ┌──────────┐
│  TUI    │ ────────────────► │  Server  │
│  (CLI)  │ ◄──────────────── │  (Hono)  │
└─────────┘                   └──────────┘
                                   │
┌─────────┐     HTTP/WS           │
│  Web    │ ────────────────► ────┘
│  App    │ ◄────────────────
└─────────┘

┌─────────┐
│ Desktop │  → 内嵌 Web App → 同上
└─────────┘
```

TUI 和 Web App 都通过 SDK 客户端与 Server 通信。Server 内部调用各个 Service 层处理请求。

## API 端点（主要）

| 端点 | 方法 | 功能 |
|------|------|------|
| `/session` | GET | 列出会话 |
| `/session` | POST | 创建会话 |
| `/session/:id/message` | POST | 发送消息 |
| `/session/:id` | GET | 获取会话详情 |
| `/agent` | GET | 列出 Agent |
| `/model` | GET | 列出模型 |
| `/provider` | GET | 列出提供者 |
| `/config` | GET/PUT | 配置管理 |

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/server/server.ts` | Server 启动和路由 |
| `src/server/` | Server 模块全部 |
| `packages/sdk/js/` | JS SDK 客户端 |
