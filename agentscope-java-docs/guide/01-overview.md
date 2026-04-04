# 01 — 项目总览

## AgentScope Java 是什么

AgentScope Java 是阿里巴巴开源的 **面向 Agent 的编程框架**，用于构建基于 LLM 的智能体应用。核心特点：

- **响应式架构** — 基于 Project Reactor，全异步非阻塞
- **ReAct 推理** — 内置推理-行动循环，自动工具选择和执行
- **多 Provider** — 支持 DashScope、OpenAI、Gemini、Anthropic、Ollama
- **插件化** — Hook 系统实现非侵入式扩展
- **生产级** — 优雅关闭、OpenTelemetry 追踪、分布式 Session

## 技术栈一览

| 层面 | 技术选型 |
|------|---------|
| 语言 | Java 17+ |
| 构建 | Maven (多模块) |
| 异步框架 | Project Reactor (Mono/Flux) |
| JSON 序列化 | Jackson |
| Schema 生成 | Victools JSON Schema Generator |
| Schema 验证 | networknt JSON Schema Validator (Draft 2020-12) |
| MCP 协议 | io.modelcontextprotocol.sdk:mcp |
| 链路追踪 | OpenTelemetry API + OTLP |
| HTTP 客户端 | OkHttp3 |
| LLM SDK | DashScope SDK、Anthropic Java SDK、Google GenAI SDK |

## 仓库结构

```
agentscope-java/
├── agentscope-core/                    # 核心框架（最重要）
│   └── src/main/java/io/agentscope/core/
│       ├── agent/                      # Agent 接口和基类
│       ├── message/                    # Msg、ContentBlock 消息模型
│       ├── memory/                     # Memory、LongTermMemory
│       ├── model/                      # Model 接口、ChatModelBase
│       ├── tool/                       # Toolkit、Tool、ToolExecutor
│       │   └── mcp/                    # MCP 协议集成
│       ├── hook/                       # Hook 事件系统
│       ├── formatter/                  # 各 Provider 消息格式转换
│       │   ├── openai/
│       │   ├── anthropic/
│       │   ├── gemini/
│       │   ├── dashscope/
│       │   └── ollama/
│       ├── session/                    # Session 持久化接口
│       ├── state/                      # StateModule 状态管理
│       ├── rag/                        # Knowledge、RAG 接口
│       ├── plan/                       # PlanNotebook 计划系统
│       ├── skill/                      # Skill 技能系统
│       ├── pipeline/                   # Pipeline 编排接口
│       ├── tracing/                    # Tracer 追踪接口
│       ├── shutdown/                   # GracefulShutdownManager
│       └── ReActAgent.java             # 核心 Agent 实现（~1743 行）
│
├── agentscope-extensions/              # 25+ 扩展模块
│   ├── agentscope-extensions-studio/           # 可视化调试
│   ├── agentscope-extensions-session-redis/    # Redis Session
│   ├── agentscope-extensions-session-mysql/    # MySQL Session
│   ├── agentscope-extensions-mem0/             # Mem0 长期记忆
│   ├── agentscope-extensions-reme/             # ReMe 记忆
│   ├── agentscope-extensions-rag-simple/       # 自建 RAG
│   ├── agentscope-extensions-rag-bailian/      # 百炼知识库
│   ├── agentscope-extensions-rag-dify/         # Dify RAG
│   ├── agentscope-extensions-rag-ragflow/      # RagFlow
│   ├── agentscope-extensions-rag-haystack/     # Haystack
│   ├── agentscope-extensions-a2a/              # A2A 协议
│   ├── agentscope-extensions-rocketmq/         # RocketMQ 通信
│   ├── agentscope-extensions-nacos/            # Nacos 服务发现
│   ├── agentscope-extensions-kotlin/           # Kotlin 协程
│   ├── agentscope-extensions-training/         # 训练数据收集
│   ├── agentscope-extensions-higress/          # API 网关
│   ├── agentscope-spring-boot-starters/        # Spring Boot 集成
│   ├── agentscope-micronaut-extensions/        # Micronaut 集成
│   └── agentscope-quarkus-extensions/          # Quarkus 原生镜像
│
├── agentscope-examples/                # 23 个示例项目
│   ├── quickstart/                     # 基础功能示例
│   ├── advanced/                       # 高级功能（RAG、LTM、Studio）
│   ├── multiagent-patterns/            # 多 Agent 模式
│   │   ├── supervisor/                 # 监督者模式
│   │   ├── handoffs/                   # 交接模式
│   │   ├── subagent/                   # 子 Agent 模式
│   │   ├── pipeline/                   # 顺序/并行/循环管线
│   │   ├── routing/                    # 动态路由
│   │   ├── workflow/                   # 自定义工作流
│   │   └── skills/                     # 渐进式技能加载
│   ├── a2a/                            # A2A 分布式协作
│   ├── boba-tea-shop/                  # 奶茶店完整业务示例
│   ├── hitl-chat/                      # 人工审批示例
│   ├── plan-notebook/                  # 计划管理示例
│   ├── werewolf/                       # 狼人杀多 Agent 游戏
│   └── graceful-shutdown/              # 优雅关闭示例
│
├── agentscope-dependencies-bom/        # 依赖版本统一管理
└── agentscope-distribution/            # 分发打包
```

## 与 Pokkit 的关键差异

| 维度 | AgentScope Java | Pokkit |
|------|----------------|--------|
| 异步模型 | Reactor (Mono/Flux) 全异步 | 阻塞式 while(true) |
| Agent 定义 | 接口继承层次 + Builder | 单一 AgenticLoop + AgentConfig |
| 工具注册 | @Tool 注解 + JSON Schema 自动生成 | Tool 接口 + 手写 Schema |
| LLM 适配 | 自研 Formatter 体系 | Spring AI 抽象层 |
| 扩展机制 | Hook 事件系统 | 无（硬编码在循环中） |
| 状态持久化 | StateModule + 多后端 Session | SQLite 硬编码 |
| 代码规模 | 数万行 + 25 扩展模块 | ~2000 行单模块 |

## 核心包路径

所有核心代码在 `io.agentscope.core` 包下，关键入口文件：

```
io.agentscope.core.ReActAgent           — 主力 Agent 实现
io.agentscope.core.agent.Agent          — Agent 顶层接口
io.agentscope.core.agent.AgentBase      — Agent 基类
io.agentscope.core.message.Msg          — 消息模型
io.agentscope.core.tool.Toolkit         — 工具注册和执行
io.agentscope.core.model.Model          — LLM 抽象接口
io.agentscope.core.hook.Hook            — Hook 扩展接口
io.agentscope.core.memory.Memory        — 记忆接口
io.agentscope.core.session.Session      — Session 持久化接口
```
