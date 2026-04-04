# 15 — 扩展模块总览

## 扩展一览表

### 可视化与 UI

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-studio` | 实时监控调试，OpenTelemetry 追踪 | OkHttp, Socket.IO, OpenTelemetry |
| `agentscope-extensions-agui` | AG-UI 协议，前端集成 | — |
| `agentscope-extensions-chat-completions-web` | OpenAI 兼容 Chat API | Reactor |

### Session 存储

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-session-redis` | Redis 分布式 Session | Redisson, Jedis, Lettuce |
| `agentscope-extensions-session-mysql` | MySQL 关系型 Session | MySQL JDBC |

### 记忆系统

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-mem0` | Mem0 长期记忆服务 | OkHttp |
| `agentscope-extensions-reme` | ReMe 情景记忆 | OkHttp |
| `agentscope-extensions-autocontext-memory` | 自动上下文压缩 | — |

### RAG 后端

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-rag-simple` | 自建 RAG | Qdrant/Milvus/pgvector/ES, PDFBox, POI |
| `agentscope-extensions-rag-bailian` | 阿里云百炼 | Bailian SDK |
| `agentscope-extensions-rag-dify` | Dify 平台 | OkHttp |
| `agentscope-extensions-rag-ragflow` | RagFlow 框架 | OkHttp |
| `agentscope-extensions-rag-haystack` | Haystack 框架 | OkHttp |

### 分布式与通信

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-a2a-client` | A2A 协议客户端 | — |
| `agentscope-extensions-a2a-server` | A2A 协议服务端 | — |
| `agentscope-extensions-rocketmq` | RocketMQ 消息传输 | RocketMQ Client |
| `agentscope-extensions-nacos-a2a` | Nacos 服务发现 | Nacos SDK |
| `agentscope-extensions-nacos-prompt` | Nacos 配置中心 Prompt 管理 | Nacos SDK |
| `agentscope-extensions-nacos-skill` | Nacos 技能发现 | Nacos SDK |

### 调度

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-scheduler-quartz` | Quartz 定时调度 | Quartz |
| `agentscope-extensions-scheduler-xxl-job` | XXL-Job 分布式调度 | XXL-Job |

### 技能仓库

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-skill-git-repository` | Git 技能仓库 | JGit |
| `agentscope-extensions-skill-mysql-repository` | MySQL 技能仓库 | MySQL JDBC |

### 框架集成

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-spring-boot-starter` | Spring Boot 自动配置 | Spring Boot |
| `agentscope-a2a-spring-boot-starter` | A2A Spring Boot 支持 | Spring Boot |
| `agentscope-agui-spring-boot-starter` | AG-UI Spring Boot 支持 | Spring Boot |
| `agentscope-chat-completions-web-starter` | Chat API Spring Boot | Spring Boot |
| `agentscope-nacos-spring-boot-starter` | Nacos Spring Boot | Spring Boot |
| `agentscope-micronaut-extension` | Micronaut DI 支持 | Micronaut |
| `agentscope-quarkus-extension` | Quarkus 原生镜像 | Quarkus, GraalVM |

### 其他

| 模块 | 功能 | 关键依赖 |
|------|------|---------|
| `agentscope-extensions-kotlin` | Kotlin 协程支持 | kotlinx-coroutines |
| `agentscope-extensions-training` | 训练数据收集导出 | Parquet, Hadoop |
| `agentscope-extensions-higress` | API 网关集成 | MCP SDK |

## 示例项目速查

### 入门

| 示例 | 展示功能 |
|------|---------|
| `quickstart/BasicChat` | 基础对话 |
| `quickstart/ToolCalling` | 工具调用 |
| `quickstart/StructuredOutput` | 结构化输出 |
| `quickstart/McpTool` | MCP 工具集成 |
| `quickstart/Hook` | Hook 事件系统 |
| `quickstart/Session` | Session 持久化 |
| `quickstart/Interruption` | 用户中断 |
| `quickstart/StreamingWeb` | SSE 流式 Web API |
| `quickstart/ToolGroup` | 工具分组管理 |

### 多 Agent 模式

| 示例 | 展示功能 |
|------|---------|
| `multiagent-patterns/supervisor` | 监督者模式 |
| `multiagent-patterns/handoffs` | Agent 交接 |
| `multiagent-patterns/subagent` | 子 Agent 委托 |
| `multiagent-patterns/pipeline` | 顺序/并行/循环管线 |
| `multiagent-patterns/routing` | 动态路由 |
| `multiagent-patterns/workflow` | 自定义工作流（RAG Agent, SQL Agent） |
| `multiagent-patterns/skills` | 渐进式技能加载 |

### 完整应用

| 示例 | 展示功能 |
|------|---------|
| `boba-tea-shop` | 完整业务系统（多 Agent + RAG + A2A + MCP） |
| `werewolf` | 多 Agent 游戏（狼人杀） |
| `werewolf-hitl` | 狼人杀 + 人类玩家 |
| `hitl-chat` | 人工审批 + MCP |
| `plan-notebook` | 计划管理 + SSE |

### 框架集成

| 示例 | 展示功能 |
|------|---------|
| `micronaut` | Micronaut 框架集成 |
| `quarkus` | Quarkus 原生镜像 |
| `a2a` | A2A + Nacos 分布式 |
| `a2a-rocketmq` | A2A + RocketMQ |
| `agui` | AG-UI 前端协议 |
| `chat-completions-web` | OpenAI 兼容 API |
| `chat-tts` | 语音合成集成 |
| `graceful-shutdown` | 优雅关闭 |
| `model-request-compression` | HTTP 压缩优化 |
