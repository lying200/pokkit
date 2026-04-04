# Pokkit 缺口分析

基于 AgentScope Java 的架构，按优先级分析 Pokkit 当前缺失的生产级功能。

## 第一层：架构基础（不做就无法扩展）

### 1. Hook / 事件系统

**AgentScope 做法**：10+ 种事件，优先级排序，可插拔
**Pokkit 现状**：所有逻辑硬编码在 `AgenticLoop.run()` 的 while 循环中

**为什么最重要**：后续几乎所有功能（RAG、HITL、结构化输出、追踪）都依赖 Hook 实现。没有 Hook 意味着每加一个功能都要改核心循环。

**Pokkit 实现建议**：
```java
public interface Hook {
    Object onEvent(Object event);
    default int priority() { return 100; }
}

// 事件类型（可以从最小集开始）
record PreReasoningEvent(List<Message> messages) {}
record PostReasoningEvent(ChatResponse response) {}
record PreActingEvent(String toolName, String args) {}
record PostActingEvent(String toolName, String result) {}
```

不需要 Mono/Flux，在 Pokkit 的阻塞模型中用同步 Hook 即可。

### 2. 工具并行执行 + 超时

**AgentScope 做法**：ToolExecutor 支持并行执行、可配置超时、指数退避重试
**Pokkit 现状**：顺序执行，BashTool 30s 超时，其他无超时

**Pokkit 实现建议**：
```java
// 并行执行
List<Future<String>> futures = toolCalls.stream()
    .map(tc -> executor.submit(() -> tool.execute(tc.arguments())))
    .toList();

// 超时
future.get(timeout, TimeUnit.SECONDS);
```

### 3. 错误恢复

**AgentScope 做法**：工具错误不传播，生成合成错误结果让 Agent 自行处理
**Pokkit 现状**：try-catch 打日志

**关键改变**：工具执行失败时，不跳过，而是返回错误信息给 LLM：
```java
try {
    result = tool.execute(args);
} catch (Exception e) {
    result = "Tool execution failed: " + e.getMessage();
    // 不 return，继续循环让 LLM 处理错误
}
```

## 第二层：扩展能力（打开功能边界）

### 4. 结构化输出

**AgentScope 做法**：临时工具 + StructuredOutputHook + 自动重试
**Pokkit 实现路径**：有了 Hook 后自然可以实现

### 5. MCP 支持

**AgentScope 做法**：McpClientWrapper 抽象，McpTool 包装
**Pokkit 实现路径**：添加 MCP SDK 依赖，为每个远程工具创建 Tool 实现

### 6. LLM 重试

**AgentScope 做法**：ExecutionConfig 统一配置，区分可重试/不可重试错误
**Pokkit 实现路径**：在 LLM 调用处添加 retry 逻辑

### 7. 状态持久化抽象

**AgentScope 做法**：StateModule + Session 接口，可切换后端
**Pokkit 实现路径**：抽取 SessionRepository 为接口

## 第三层：可运维（上线必备）

### 8. 链路追踪

**AgentScope 做法**：Tracer 接口 + OpenTelemetry
**Pokkit 实现路径**：在关键点添加 Micrometer 或 OpenTelemetry span

### 9. Token 精确计量

**AgentScope 做法**：ChatUsage 记录每次 input/output tokens
**Pokkit 实现路径**：从 Spring AI 的 ChatResponse 中提取 usage 信息

### 10. 优雅关闭

**AgentScope 做法**：GracefulShutdownManager + Session 自动保存
**Pokkit 实现路径**：Spring Boot shutdown hook + 保存进行中的 Session

### 11. 用户中断

**AgentScope 做法**：AtomicBoolean 中断标记 + 协作式检查
**Pokkit 实现路径**：在循环中添加中断检查

## 第四层：高级功能（按需选做）

| 功能 | 复杂度 | 依赖 |
|------|--------|------|
| RAG 知识库 | 中 | Hook 系统 |
| 长期记忆 | 中 | Hook 系统 + 外部服务 |
| PlanNotebook | 低 | 只需新工具 + 状态持久化 |
| A2A 分布式 | 高 | 网络通信 + 服务发现 |
| 多模态消息 | 中 | 消息模型重构 |
| Skill 系统 | 低 | 文件加载 + 工具注册 |

## 建议迭代路径

```
v0.8  Hook 系统（最小可用版本：Pre/PostReasoning + Pre/PostActing）
v0.9  工具并行执行 + 超时 + 错误恢复
v0.10 LLM 重试 + Token 计量
v0.11 结构化输出（利用 Hook 实现）
v0.12 MCP 工具支持
v0.13 链路追踪 (OpenTelemetry)
v0.14 优雅关闭 + 用户中断
v0.15 PlanNotebook（纯工具实现，不改核心）
```

核心原则：**先 Hook，后一切**。Hook 是架构分水岭，有了它后续功能都可以非侵入式添加。
