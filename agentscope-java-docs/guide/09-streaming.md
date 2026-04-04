# 09 — 流式架构

## 两种使用模式

### 模式 1：call() — 拿最终结果

```java
Mono<Msg> result = agent.call(userMsg);
Msg msg = result.block();  // 阻塞等待最终结果
```

### 模式 2：stream() — 实时事件流

```java
Flux<Event> events = agent.stream(List.of(userMsg), streamOptions);

events.subscribe(event -> {
    switch (event.getType()) {
        case REASONING -> {
            // LLM 正在输出
            System.out.print(event.getMessage().getTextContent());
            if (event.isLast()) System.out.println();
        }
        case TOOL_RESULT -> {
            // 工具执行完毕
            System.out.println("[Tool] " + event.getMessage().getTextContent());
        }
        case SUMMARY -> {
            // 达到 maxIters，生成摘要
            System.out.println("[Summary] " + event.getMessage().getTextContent());
        }
        case AGENT_RESULT -> {
            // 最终结果（可选）
        }
    }
});
```

## Event 类型

```java
public class Event {
    private final EventType type;
    private final Msg message;
    private final boolean isLast;    // 同一消息的最后一个 chunk
}

public enum EventType {
    REASONING,       // LLM 推理输出（逐 chunk）
    TOOL_RESULT,     // 工具执行结果
    HINT,            // RAG/记忆/计划等提示信息
    AGENT_RESULT,    // 最终结果（默认不推送，需配置）
    SUMMARY,         // 达到 maxIters 的摘要
    ALL              // 元值：订阅所有类型
}
```

### 流式语义

同一消息 ID 的多个 Event 构成一次完整输出：

```
Event(REASONING, msg(id="abc"), isLast=false)   ← "今天"
Event(REASONING, msg(id="abc"), isLast=false)   ← "天气"
Event(REASONING, msg(id="abc"), isLast=true)    ← "晴朗" (完成)
Event(TOOL_RESULT, msg(id="def"), isLast=true)  ← 工具结果
Event(REASONING, msg(id="ghi"), isLast=false)   ← 新一轮推理...
```

用 `isLast()` 判断何时可以安全处理完整消息。

## StreamOptions

```java
StreamOptions options = StreamOptions.builder()
    .includeThinking(true)     // 包含 ThinkingBlock
    .build();
```

## 实现机制

`stream()` 在 `AgentBase` 中实现，通过动态 Hook 捕获事件：

```
agent.stream(msgs, options)
  │
  ▼
Flux.create(sink -> {
    // 1. 创建临时 StreamingHook
    StreamingHook hook = new StreamingHook(sink, options);

    // 2. 加入 Hook 列表
    addHook(hook);

    // 3. 执行 call()（内部 Hook 会捕获事件）
    call(msgs).subscribe(
        finalMsg -> sink.next(Event(AGENT_RESULT, finalMsg)),
        sink::error,
        sink::complete
    );

    // 4. 完成后移除 Hook
    doFinally -> hooks.remove(hook);
});
```

`StreamingHook` 在每个 HookEvent 中将内容转为 Event 推送到 sink：

```
PreReasoningEvent  → (不推送)
ReasoningChunkEvent → sink.next(Event(REASONING, chunk))
PostReasoningEvent  → (不推送)
ActingChunkEvent    → sink.next(Event(TOOL_RESULT, chunk))
PostActingEvent     → sink.next(Event(TOOL_RESULT, result))
...
```

## Reactive 编程模型核心概念

对于从 Pokkit 的阻塞式转向 AgentScope 的响应式，需要理解：

### Mono<T> — 0 或 1 个结果

```java
// 创建
Mono<String> mono = Mono.just("hello");
Mono<String> lazy = Mono.defer(() -> Mono.just(compute()));
Mono<Void> empty = Mono.empty();

// 链式操作
mono.map(s -> s.toUpperCase())             // 转换
    .flatMap(s -> callApi(s))              // 异步操作
    .doOnNext(s -> log.info(s))            // 副作用
    .onErrorResume(e -> Mono.just("fallback"))  // 错误恢复
    .subscribe(result -> ...);             // 订阅执行
```

### Flux<T> — 0 到 N 个结果

```java
Flux<ChatResponse> stream = model.stream(msgs, tools, options);

stream.doOnNext(chunk -> print(chunk))     // 每个 chunk
      .last()                               // 取最后一个
      .subscribe(finalResponse -> ...);
```

### 关键原则

1. **什么都不发生直到 subscribe**：创建 Mono/Flux 只是描述操作，不执行
2. **不阻塞线程**：用 flatMap 链式组合，不用 `.block()`（除了终端）
3. **错误在链中传播**：用 `onErrorResume` 处理，不是 try-catch
4. **背压**：Flux 自动处理消费速度慢于生产速度的情况

### 对比 Pokkit 的阻塞模型

```java
// Pokkit（阻塞）
while (true) {
    ChatResponse response = streamAndPrint(prompt);  // 阻塞等待
    if (!response.hasToolCalls()) return;
    for (ToolCall tc : toolCalls) {
        String result = tool.execute(tc.arguments()); // 阻塞执行
    }
}

// AgentScope（响应式）
executeIteration(0)  // 返回 Mono<Msg>，不阻塞
    → reasoning(0)
        → model.stream().then(...)     // 流式 LLM 调用
        → acting(0)
            → toolkit.callTools(...)   // 并行工具执行
        → executeIteration(1)          // 递归继续
```

AgentScope 用**递归 Mono 链**替代 `while(true)`：

```java
private Mono<Msg> executeIteration(int iter) {
    return reasoning(iter)
        .flatMap(msg -> {
            if (isFinished(msg)) return Mono.just(msg);
            return acting(iter)
                .flatMap(result -> executeIteration(iter + 1));
        });
}
```

这使得：
- 单线程可以处理大量并发 Agent
- 每个 Agent 不占独立线程
- 天然支持流式和取消
