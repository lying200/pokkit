# AgentScope Java 学习文档

## 项目简介

AgentScope Java 是阿里巴巴开源的 Java Agent 框架，面向生产级 LLM Agent 应用开发。
基于 Project Reactor 响应式架构，支持 ReAct 推理循环、多 Provider、工具调用、RAG、多 Agent 协作等完整能力。

- 仓库：`github.com/modelscope/agentscope` (Java 版)
- 语言：Java 17+
- 构建：Maven 多模块
- 核心依赖：Project Reactor、Jackson、JSON Schema Generator、MCP SDK

## 目录结构

- `guide/` — 按模块拆解的系统性学习文档
- `comparison/` — 与 Pokkit 的对比分析

## 推荐学习路径

### 第一阶段：宏观理解
1. [项目总览](guide/01-overview.md) — 技术栈、仓库结构、模块职责
2. [架构全景](guide/02-architecture.md) — 分层架构、核心抽象、数据流

### 第二阶段：核心机制
3. [Agent 系统](guide/03-agent-system.md) — 接口层次、ReActAgent、生命周期
4. [消息模型](guide/04-message-model.md) — Msg、ContentBlock、MsgRole
5. [工具系统](guide/05-tool-system.md) — 注册、Schema、执行管线、MCP
6. [Model Provider](guide/06-model-provider.md) — LLM 抽象、Formatter、多 Provider 适配

### 第三阶段：扩展机制
7. [Hook 系统](guide/07-hook-system.md) — 事件类型、可修改事件、优先级
8. [记忆与状态](guide/08-memory.md) — 短期记忆、长期记忆、Session 持久化
9. [流式架构](guide/09-streaming.md) — Reactive 模型、Event、Flux 事件流

### 第四阶段：高级功能
10. [结构化输出](guide/10-structured-output.md) — JSON Schema 约束、自动重试
11. [多 Agent 编排](guide/11-pipeline.md) — Pipeline、Supervisor、Handoff、子 Agent
12. [RAG 知识库](guide/12-rag.md) — Knowledge 接口、多后端、注入模式
13. [计划系统](guide/13-plan-notebook.md) — PlanNotebook、子任务管理

### 第五阶段：生产能力
14. [生产特性](guide/14-production.md) — 优雅关闭、链路追踪、执行配置、中断
15. [扩展模块总览](guide/15-extensions.md) — 25+ 扩展模块速查表

### 对比分析
- [Pokkit 缺口分析](comparison/pokkit-gaps.md) — 与 Pokkit 对比，识别生产级功能差距
