# 03 — Agent 系统

## 接口层次

```
Agent (顶层组合接口)
├── CallableAgent       — 处理消息，返回 Mono<Msg>
├── StreamableAgent     — 流式执行，返回 Flux<Event>
└── ObservableAgent     — 被动观察，返回 Mono<Void>

AgentBase (抽象基类，实现 Agent)
├── 资源管理 (Mono.using)
├── Hook 通知链
├── 中断机制
├── 状态持久化
└── 消息广播 (MsgHub)

StructuredOutputCapableAgent (extends AgentBase)
├── 结构化输出基础设施
├── 临时工具注册 + Hook 驱动重试
└── Schema 验证

ReActAgent (extends StructuredOutputCapableAgent)  ← 主力实现
├── ReAct 推理-行动循环
├── Memory 管理
├── Toolkit 集成
├── PlanNotebook（可选）
└── Builder 模式配置
```

## CallableAgent 接口

Agent 处理消息的核心能力。所有方法返回 `Mono<Msg>`（异步、非阻塞）：

```java
public interface CallableAgent {
    Mono<Msg> call();                                    // 无新输入，继续对话
    Mono<Msg> call(Msg msg);                             // 单条消息
    Mono<Msg> call(List<Msg> msgs);                      // 多条消息（核心方法）
    Mono<Msg> call(Msg msg, Class<?> structuredModel);   // 结构化输出
    Mono<Msg> call(List<Msg> msgs, JsonNode schema);     // JSON Schema 约束
}
```

**对比 Pokkit**：Pokkit 的 `AgenticLoop.run()` 是 void 方法，阻塞执行，直接修改传入的 conversationHistory。AgentScope 返回 Mono，不修改外部状态。

## StreamableAgent 接口

实时事件流，用于 UI 展示和监控：

```java
public interface StreamableAgent {
    Flux<Event> stream(List<Msg> msgs, StreamOptions options);
    Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel);
}
```

返回 `Flux<Event>`，每个 Event 包含类型（REASONING / TOOL_RESULT / SUMMARY 等）和消息内容。详见 [09-streaming.md](09-streaming.md)。

## ObservableAgent 接口

被动观察，不产生回复。用于多 Agent 场景中让旁观者 Agent 接收上下文：

```java
public interface ObservableAgent {
    Mono<Void> observe(Msg msg);
    Mono<Void> observe(List<Msg> msgs);
}
```

## AgentBase — 基础设施层

`AgentBase` 是所有 Agent 实现的基类（约 798 行），提供：

### 资源生命周期

```java
public final Mono<Msg> call(List<Msg> msgs) {
    return Mono.using(
        this::acquireExecution,
        resource -> tracerRegistry.callAgent(this, msgs, () ->
            notifyPreCall(msgs)
                .flatMap(this::doCall)     // 子类实现
                .flatMap(this::notifyPostCall)
        ),
        this::releaseExecution,
        true);
}
```

- `acquireExecution()` — 设置 `running = true`
- `releaseExecution()` — 设置 `running = false`，无论成功失败都执行

### Hook 通知链

```java
private Mono<List<Msg>> notifyPreCall(List<Msg> msgs) {
    // 按优先级排序 hooks，依次调用 onEvent(PreCallEvent)
    // 每个 Hook 可以修改消息列表
}

private Mono<Msg> notifyPostCall(Msg finalMsg) {
    // 调用 hooks 的 onEvent(PostCallEvent)
    // 广播到 MsgHub 订阅者
}
```

### 协作式中断

```java
// 外部调用（可从其他线程）
agent.interrupt();
agent.interrupt(userMsg);  // 带用户消息的中断

// 内部检查点（在 Mono 链中）
protected Mono<Void> checkInterruptedAsync() {
    return Mono.defer(() ->
        interruptFlag.get()
            ? Mono.error(new InterruptedException("Agent execution interrupted"))
            : Mono.empty());
}
```

中断检查嵌入在推理循环的关键位置（每次 LLM chunk、每次迭代开始前），实现**协作式**而非强制中断。

### 流式支持

`stream()` 方法动态创建临时 `StreamingHook`，捕获执行过程中的事件：

```java
private Flux<Event> createEventStream(StreamOptions options, Supplier<Mono<Msg>> callSupplier) {
    return Flux.create(sink -> {
        StreamingHook streamingHook = new StreamingHook(sink, options);
        addHook(streamingHook);           // 临时加入
        callSupplier.get()
            .doFinally(signal -> hooks.remove(streamingHook))  // 执行完移除
            .subscribe(
                finalMsg -> { /* emit AGENT_RESULT */ },
                sink::error
            );
    });
}
```

## ReActAgent — 核心实现

ReActAgent（约 1743 行）是框架的主力 Agent，实现 ReAct（Reasoning + Acting）循环。

### 依赖注入

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个有用的助手")
    .model(chatModel)                              // 必须：LLM 模型
    .toolkit(toolkit)                              // 工具集
    .memory(new InMemoryMemory())                  // 对话记忆
    .maxIters(10)                                  // 最大迭代次数
    .modelExecutionConfig(ExecutionConfig.builder() // LLM 调用配置
        .timeout(Duration.ofMinutes(2))
        .maxAttempts(3)
        .build())
    .toolExecutionConfig(ExecutionConfig.TOOL_DEFAULTS)
    .generateOptions(GenerateOptions.builder()
        .temperature(0.7)
        .build())
    .hooks(List.of(customHook))                    // Hook 列表
    .longTermMemory(mem0Client)                    // 可选：长期记忆
    .planNotebook(planNotebook)                    // 可选：任务规划
    .build();
```

### 推理阶段 (reasoning)

```
reasoning(iter)
├── 检查 maxIters 限制
├── checkInterruptedAsync()
├── 通知 PreReasoningEvent（Hook 可修改消息和 GenerateOptions）
├── model.stream(messages, toolSchemas, options)
│   └── 逐 chunk 处理：
│       ├── checkInterruptedAsync()（每个 chunk 都检查）
│       ├── ReasoningContext.processChunk()（累积）
│       └── 通知 ReasoningChunkEvent（流式输出）
├── 构建最终 Msg
├── 通知 PostReasoningEvent
│   ├── Hook 可调用 stopAgent() → HITL 停止
│   ├── Hook 可调用 gotoReasoning() → 结构化输出重试
│   └── 正常继续
├── 判断 isFinished（无 ToolUseBlock = 完成）
│   ├── 完成 → return Msg
│   └── 有工具调用 → acting(iter)
```

### 行动阶段 (acting)

```
acting(iter)
├── 提取 pending ToolUseBlock 列表
├── 通知 PreActingEvent（Hook 可修改工具参数）
├── toolkit.callTools(toolCalls, config, agent, context)
│   └── 对每个工具：
│       ├── Schema 校验
│       ├── 参数合并（preset + input）
│       ├── tool.callAsync(param) → Mono<ToolResultBlock>
│       ├── 超时/重试/关闭保护
│       └── 错误 → 生成合成错误结果（不传播异常）
├── 分离成功结果 vs 挂起结果 (ToolSuspendException)
├── 对每个成功结果通知 PostActingEvent
│   └── Hook 可调用 stopAgent() → HITL 停止
├── 有挂起工具 → 返回 suspended Msg
└── 继续 executeIteration(iter + 1)
```

### 摘要阶段 (summarizing)

当达到 maxIters 时触发，让 LLM 对当前进展做总结：

```
summarizing()
├── 准备摘要消息列表
├── 通知 PreSummaryEvent
├── model.stream() 生成摘要
├── 通知 PostSummaryEvent
└── 返回 Msg(generateReason = MAX_ITERATIONS)
```

### 完成判断

```java
private boolean isFinished(Msg msg) {
    if (msg == null) return true;
    List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
    return toolCalls.isEmpty();  // 没有工具调用 = 任务完成
}
```

**对比 Pokkit**：逻辑相同——`if (!response.hasToolCalls()) return`。但 AgentScope 额外支持 suspended tools、HITL stop、gotoReasoning 等分支。

### 错误恢复

工具执行错误不会中断 Agent 循环：

```java
private Mono<List<Entry<ToolUseBlock, ToolResultBlock>>> executeToolCalls(...) {
    return toolkit.callTools(...)
        .onErrorResume(Exception.class, error -> {
            if (error instanceof InterruptedException) {
                return Mono.error(error);  // 中断异常保留
            }
            // 其他异常 → 生成合成错误结果
            List<Entry<...>> errorResults = toolCalls.stream()
                .map(tc -> Map.entry(tc, buildErrorToolResult(tc, error.getMessage())))
                .toList();
            return Mono.just(errorResults);
        });
}
```

Agent 会在下一轮推理中"看到"错误，自行决定重试或换策略。

### 状态持久化

ReActAgent 实现 `StateModule`，可保存/恢复完整状态：

```java
agent.saveTo(session, sessionKey);   // 保存到 Session
agent.loadFrom(session, sessionKey); // 从 Session 恢复

// 保存内容：
// - AgentMetaState (id, name, description, sysPrompt)
// - Memory messages（如果 memoryManaged = true）
// - Toolkit activeGroups（如果 toolkitManaged = true）
// - PlanNotebook state（如果 planNotebookManaged = true）
```

**对比 Pokkit**：Pokkit 的 Session 只存消息历史和权限规则，没有 Agent 元数据和工具状态的持久化。
