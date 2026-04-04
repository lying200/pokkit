# 02 — 架构全景

## 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    应用层 (Examples)                      │
│   quickstart / multiagent-patterns / boba-tea-shop       │
├─────────────────────────────────────────────────────────┤
│                   框架集成层 (Starters)                    │
│   Spring Boot / Micronaut / Quarkus 自动配置              │
├─────────────────────────────────────────────────────────┤
│                   扩展层 (Extensions)                     │
│   RAG 后端 / Session 存储 / LTM / 调度 / A2A / Studio    │
├─────────────────────────────────────────────────────────┤
│                     核心层 (Core)                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │  Agent   │ │  Model   │ │  Tool    │ │  Hook     │  │
│  │  Memory  │ │ Formatter│ │  MCP     │ │  Event    │  │
│  │  Session │ │  Tracer  │ │ Pipeline │ │ Shutdown  │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘  │
├─────────────────────────────────────────────────────────┤
│                   基础设施层                               │
│   Project Reactor / Jackson / OkHttp / OpenTelemetry     │
└─────────────────────────────────────────────────────────┘
```

## 核心抽象关系

```
ReActAgent (核心实现)
├── 实现 Agent 接口
│   ├── CallableAgent  → call(msgs) : Mono<Msg>
│   ├── StreamableAgent → stream(msgs, opts) : Flux<Event>
│   └── ObservableAgent → observe(msgs) : Mono<Void>
│
├── 依赖注入
│   ├── Model          → LLM 调用（stream 接口）
│   ├── Memory         → 对话历史管理
│   ├── Toolkit        → 工具注册和执行
│   ├── List<Hook>     → 生命周期拦截
│   └── PlanNotebook   → 可选的任务规划
│
├── 配置
│   ├── ExecutionConfig (model) → LLM 调用超时/重试
│   ├── ExecutionConfig (tool)  → 工具执行超时/重试
│   └── GenerateOptions         → 温度/topP 等采样参数
│
└── 状态
    ├── StateModule    → saveTo/loadFrom Session
    └── AtomicBoolean  → 中断标记
```

## 请求完整数据流

```
用户输入
  │
  ▼
Agent.call(List<Msg>)
  │
  ├── acquireExecution()          ← Mono.using 资源管理
  ├── resetInterruptFlag()
  ├── notifyPreCall(hooks)        ← Hook 可修改输入
  │
  ▼
ReActAgent.doCall(msgs)
  │
  ├── addToMemory(msgs)
  │
  ▼
┌─ executeIteration(iter) ──────────────────────────────┐
│                                                        │
│  reasoning(iter)                                       │
│  ├── checkInterruptedAsync()    ← 协作式中断检查        │
│  ├── notifyPreReasoningEvent()  ← Hook 可修改消息       │
│  ├── model.stream(msgs, tools, options)                │
│  │   ├── Formatter.format(msgs) → 转为 Provider 格式   │
│  │   ├── HTTP 请求 → LLM API                           │
│  │   └── Formatter.parseResponse() → ChatResponse      │
│  ├── ReasoningContext.processChunk() ← 逐 chunk 累积   │
│  ├── notifyReasoningChunk()     ← Hook 捕获流式输出     │
│  ├── notifyPostReasoning()      ← Hook 可修改/停止      │
│  │                                                     │
│  ├── isFinished? ──YES──→ return Msg                   │
│  │       │                                             │
│  │      NO (有 ToolUseBlock)                           │
│  │       │                                             │
│  ▼       ▼                                             │
│  acting(iter)                                          │
│  ├── extractPendingToolCalls()                         │
│  ├── notifyPreActingHooks()     ← Hook 可修改工具参数   │
│  ├── toolkit.callTools()                               │
│  │   ├── ToolValidator.validateInput()  ← Schema 校验  │
│  │   ├── tool.callAsync()       ← 实际执行             │
│  │   ├── applyTimeout()         ← 超时控制             │
│  │   ├── applyRetry()           ← 重试策略             │
│  │   └── applyShutdownGuard()   ← 关闭保护             │
│  ├── notifyPostActingHook()     ← Hook 可修改/停止      │
│  │                                                     │
│  └── executeIteration(iter + 1) ← 继续下一轮           │
│                                                        │
│  ── 达到 maxIters ──→ summarizing() ← 生成摘要         │
└────────────────────────────────────────────────────────┘
  │
  ▼
notifyPostCall(hooks)             ← Hook 可修改最终输出
releaseExecution()                ← 资源释放
  │
  ▼
Mono<Msg> 返回给调用方
```

## 关键设计原则

### 1. 响应式优先

所有异步操作返回 `Mono<T>` 或 `Flux<T>`，不阻塞线程：

```java
// 不是这样
Msg result = agent.callSync(msg);  // ❌ 阻塞

// 而是这样
Mono<Msg> result = agent.call(msg);  // ✅ 非阻塞
result.subscribe(msg -> ...);         // 订阅时才执行
result.block();                       // 或在终端点阻塞
```

**对比 Pokkit**：Pokkit 是 `while(true)` 阻塞循环，每次 LLM 调用阻塞当前线程。AgentScope 用 Reactor 的 Mono 链，可以在少量线程上运行大量并发 Agent。

### 2. 组合优于继承

`Agent` 接口由三个独立关注点组合：

```java
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {
    // CallableAgent:  处理消息 → Mono<Msg>
    // StreamableAgent: 流式事件 → Flux<Event>
    // ObservableAgent: 被动观察 → Mono<Void>
}
```

### 3. Memory 不在接口中

核心 `Agent` 接口**不包含**记忆管理。记忆是实现细节，由具体 Agent（如 ReActAgent）自己管理。这使得不同 Agent 可以有完全不同的记忆策略。

### 4. 资源管理模式

`AgentBase.call()` 使用 `Mono.using()` 保证资源获取和释放：

```java
public final Mono<Msg> call(List<Msg> msgs) {
    return Mono.using(
        this::acquireExecution,     // 获取资源（设置 running 标记）
        resource -> /* 执行链 */,
        this::releaseExecution,     // 释放资源（无论成功/失败）
        true);                      // eager cleanup
}
```

**对比 Pokkit**：Pokkit 没有资源管理，Agent 被 kill 就直接断开。

### 5. 错误不传播，转为工具结果

工具执行失败时，不抛异常中断循环，而是生成合成错误结果：

```java
// ToolExecutor 内部
.onErrorResume(Exception.class, error -> {
    // 不是 Mono.error(error) 传播异常
    // 而是生成错误工具结果，让 Agent 自己决定怎么处理
    return Mono.just(buildErrorToolResult(toolCall, error.getMessage()));
});
```

这使得 Agent 可以看到错误并自行修正，而不是崩溃。
