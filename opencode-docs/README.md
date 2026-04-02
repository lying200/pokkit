# OpenCode 学习文档

## 目录结构

- `guide/` — 系统性学习文档，按照推荐的学习顺序编号
- `notes/` — 零散但重要的笔记

## 专题深度剖析

- **[Agent 系统深度剖析](agents/README.md)** — 抛开表层设计，直击项目灵魂：Agent 的底层架构模型

## 推荐学习路径

### 第一阶段：宏观理解
1. [项目总览](guide/01-overview.md) — 项目是什么、技术栈、仓库结构
2. [架构全景](guide/02-architecture.md) — 分层架构、数据流、核心抽象

### 第二阶段：核心模块深入
3. [Effect 系统与依赖注入](guide/03-effect-system.md) — 理解项目的基础设施层
4. [配置系统](guide/04-config.md) — 配置层级、文件格式、加载流程
5. [Agent 系统](guide/05-agent.md) — 多 Agent 架构、权限、路由
6. [Session 与消息处理](guide/06-session.md) — 会话管理、消息结构、流式处理
7. [Tool 系统](guide/07-tool.md) — 工具注册、执行、权限检查
8. [Provider 系统](guide/08-provider.md) — LLM 提供者抽象、模型管理

### 第三阶段：扩展机制
9. [Plugin 系统](guide/09-plugin.md) — 插件发现、加载、Hook 机制
10. [Skill 系统](guide/10-skill.md) — Markdown 技能定义、发现、调用
11. [MCP 协议](guide/11-mcp.md) — Model Context Protocol 集成
12. [LSP 集成](guide/12-lsp.md) — Language Server Protocol 能力

### 第四阶段：用户界面与交互
13. [CLI 与 TUI](guide/13-cli-tui.md) — 命令行入口、终端 UI
14. [Server 与 API](guide/14-server.md) — HTTP/WebSocket 服务、API 设计
15. [权限系统](guide/15-permission.md) — 细粒度访问控制

### 第五阶段：工程实践
16. [存储层](guide/16-storage.md) — SQLite、Drizzle ORM、迁移
17. [事件总线](guide/17-bus.md) — 发布订阅、事件定义
18. [构建与测试](guide/18-build-test.md) — Monorepo 构建、测试策略
