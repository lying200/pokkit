# Agent 系统深度剖析

这里是 OpenCode 项目的灵魂——Agent 系统的底层架构分析。

抛开表层的参数校验、UI 渲染、配置解析，直击核心：**一个 LLM 驱动的 Agent 如何思考、行动、协作、自我修复。**

## 文档索引

1. [Agent 执行模型](01-execution-model.md) — Agent 不是进程，而是一个受控的 while(true) 循环
2. [Agentic Loop 详解](02-agentic-loop.md) — runLoop 的每一次迭代到底发生了什么
3. [LLM 流式处理引擎](03-streaming-engine.md) — 从 streamText 到事件分发，Processor 如何驱动一切
4. [工具执行与反馈闭环](04-tool-feedback-loop.md) — 工具不只是被调用，它的输出是下一轮思考的输入
5. [多 Agent 编排](05-multi-agent.md) — Task 工具如何实现 Agent 派生和层级协作
6. [权限即边界](06-permission-boundary.md) — 权限不是附加功能，而是 Agent 能力的定义
7. [记忆与遗忘](07-memory-compaction.md) — Compaction 和 Pruning 如何让 Agent 在有限窗口里保持长期记忆
8. [快照与时间旅行](08-snapshot-undo.md) — 基于 Git 的文件状态追踪，让每个 Step 都可回滚
9. [安全阀机制](09-safety-valves.md) — Doom Loop、MaxSteps、Abort：防止 Agent 失控的三道防线
