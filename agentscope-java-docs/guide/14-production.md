# 14 — 生产特性

## 执行配置 (ExecutionConfig)

统一的超时和重试配置，分别用于 Model 和 Tool：

```java
public class ExecutionConfig {
    private final Duration timeout;           // 单次执行超时
    private final Integer maxAttempts;        // 最大尝试次数
    private final Duration initialBackoff;    // 初始退避间隔
    private final Duration maxBackoff;        // 最大退避间隔
    private final Double backoffMultiplier;   // 指数退避因子
    private final Predicate<Throwable> retryOn;  // 重试条件
}
```

### 默认预设

**MODEL_DEFAULTS**（LLM 调用）：

```java
timeout: 5 minutes
maxAttempts: 3
initialBackoff: 2 seconds
maxBackoff: 30 seconds
backoffMultiplier: 2.0
retryOn: HTTP 429 / HTTP 5xx / Timeout / IOException
```

**TOOL_DEFAULTS**（工具执行）：

```java
timeout: 5 minutes
maxAttempts: 1 (不重试)
```

### 配置合并（分层优先级）

```java
// 高优先级 → 低优先级
ExecutionConfig merged = ExecutionConfig.mergeConfigs(
    perRequestConfig,      // 单次请求配置
    ExecutionConfig.mergeConfigs(
        agentConfig,       // Agent 级配置
        ExecutionConfig.MODEL_DEFAULTS  // 全局默认
    )
);
```

### 可重试错误

- HTTP 429 (限流)
- HTTP 5xx (服务器错误)
- TimeoutException
- IOException (网络错误)
- RateLimitException

不可重试：
- HTTP 400 (请求错误)
- HTTP 401/403 (认证错误)
- 其他 4xx 客户端错误

**对比 Pokkit**：Pokkit 没有重试机制。BashTool 有 30 秒硬编码超时，其他工具无超时。

## 优雅关闭 (GracefulShutdownManager)

### 问题

进程收到 SIGTERM（如 K8s pod 重启）时，正在运行的 Agent 可能：
- 丢失进行中的推理结果
- 工具执行中断导致不一致状态
- 用户会话丢失

### 解决方案

```java
GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
```

**状态机**：

```
RUNNING → SHUTTING_DOWN → TERMINATED
```

**活跃请求追踪**：

```java
// Agent 开始执行时注册
UUID requestId = manager.registerRequest(agent);

// Agent 完成时注销
manager.unregisterRequest(agent);

// 查看活跃请求数
int active = manager.getActiveRequestCount();
```

**关闭流程**：

```java
manager.performGracefulShutdown();
// 1. 状态 → SHUTTING_DOWN
// 2. 通知所有活跃 Agent 中断
// 3. 等待 Agent 完成（带超时）
// 4. 超时未完成 → 强制中断
// 5. 状态 → TERMINATED
```

**工具执行保护**：

ToolExecutor 中的每次工具执行都与关闭信号竞争：

```java
Mono.race(
    toolExecution,                      // 正常执行
    manager.getShutdownTimeoutSignal()  // 关闭信号
);
```

**Session 绑定**：

```java
manager.bindSession(agent, session, sessionKey);
// 关闭时自动保存 Agent 状态到 Session
```

通过 `GracefulShutdownHook`（PreCallEvent）自动绑定。

**对比 Pokkit**：Pokkit 没有关闭处理。进程 kill 后所有进行中的对话直接丢失。

## 协作式中断 (Interrupt)

### 使用方式

```java
// 从外部线程中断 Agent
agent.interrupt();                  // 简单中断
agent.interrupt(userMsg);           // 带用户消息的中断
```

### 实现机制

1. 设置 `AtomicBoolean interruptFlag = true`
2. Agent 循环中的 `checkInterruptedAsync()` 检测到标记
3. 抛出 `InterruptedException`
4. 中断处理：保存部分推理结果、清理资源
5. 返回中断信息给调用方

检查点位置：
- 每次 LLM chunk 处理后
- 每次迭代开始前
- 工具执行前

### 中断后恢复

如果绑定了 Session，中断时自动保存状态。下次 `loadFrom()` 可以恢复到中断前的状态。

**对比 Pokkit**：Pokkit 只有 `maxSteps` 硬限制。用户无法中途打断 Agent。

## 链路追踪 (Tracer)

### Tracer 接口

```java
public interface Tracer {
    Mono<Msg> callAgent(AgentBase instance, List<Msg> inputMessages,
                        Supplier<Mono<Msg>> agentCall);

    Flux<ChatResponse> callModel(ChatModelBase instance, List<Msg> inputMessages,
                                 List<ToolSchema> toolSchemas, GenerateOptions options,
                                 Supplier<Flux<ChatResponse>> modelCall);

    Mono<ToolResultBlock> callTool(Toolkit toolkit, ToolCallParam toolCallParam,
                                   Supplier<Mono<ToolResultBlock>> toolKitCall);

    <TReq, TResp, TParams> List<TReq> callFormat(
        AbstractBaseFormatter<TReq, TResp, TParams> formatter, List<Msg> msgs,
        Supplier<List<TReq>> formatCall);
}
```

### 追踪点

```
Agent.call()
  └── Tracer.callAgent()
        ├── Model.stream()
        │   └── Tracer.callModel()
        │       └── Formatter.format()
        │           └── Tracer.callFormat()
        └── Toolkit.callTools()
            └── Tracer.callTool()
```

### 默认实现

- `NoopTracer` — 默认无操作，直接委托给 Supplier
- Studio 扩展 — OpenTelemetry 实现，OTLP 导出到 Jaeger/Zipkin

### OpenTelemetry 集成 (Studio 扩展)

```
agentscope-extensions-studio 提供：
- OpenTelemetry API 集成
- OTLP 导出器
- Reactor Context 传播
- 可视化调试界面
```

追踪数据：
- Agent 调用耗时
- LLM 请求/响应/token 用量
- 工具执行耗时和结果
- 消息格式化过程

**对比 Pokkit**：Pokkit 没有任何追踪。调试只能靠 `System.out.println`。

## Token 用量追踪

### ChatUsage

```java
public class ChatUsage {
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
}
```

每次 LLM 调用返回的 `ChatResponse` 都包含 `ChatUsage`，精确记录 token 消耗。

结构化输出多轮重试时，`StructuredOutputHook` 累积所有轮次的用量。

**对比 Pokkit**：Pokkit 用 `text.length / 4` 估算 token，仅用于触发压缩。没有精确计量。

## 总结：生产能力清单

| 能力 | AgentScope | Pokkit | 重要性 |
|------|-----------|--------|--------|
| LLM 超时 | 可配置 (默认 5 min) | 无 | 高 |
| LLM 重试 | 指数退避 + 过滤 | 无 | 高 |
| 工具超时 | 可配置 | BashTool 30s | 高 |
| 工具重试 | 可配置 | 无 | 中 |
| 优雅关闭 | GracefulShutdownManager | 无 | 高 |
| 用户中断 | interrupt() 协作式 | 无 | 中 |
| 链路追踪 | OpenTelemetry | 无 | 高 |
| Token 计量 | ChatUsage 精确 | 估算 | 中 |
| 错误恢复 | 合成错误结果 | try-catch | 高 |
| Session 恢复 | 完整状态恢复 | 仅消息历史 | 中 |
