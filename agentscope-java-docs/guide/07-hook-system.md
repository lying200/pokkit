# 07 — Hook 系统

## 设计理念

Hook 是 AgentScope 的核心扩展机制。核心循环代码不动，通过 Hook 注入横切关注点：

```
没有 Hook 的世界（Pokkit 的现状）：
  AgenticLoop.run() 里 if/else 硬编码所有逻辑
  → 加日志？改循环体。加 RAG？改循环体。加审批？改循环体。

有 Hook 的世界（AgentScope）：
  ReActAgent 循环只做核心逻辑
  → 加日志？挂 Hook。加 RAG？挂 Hook。加审批？挂 Hook。
```

## Hook 接口

```java
public interface Hook {
    <T extends HookEvent> Mono<T> onEvent(T event);

    default int priority() {
        return 100;  // 数字越小优先级越高
    }
}
```

所有事件类型通过一个 `onEvent` 方法处理，用 Java pattern matching 区分：

```java
Hook myHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                // 在 LLM 推理前修改消息
                yield Mono.just(e);
            }
            case PostActingEvent e -> {
                // 在工具执行后审查结果
                yield Mono.just(e);
            }
            default -> Mono.just(event);  // 不关心的事件直接放行
        };
    }
};
```

## 事件类型全表

### 可修改事件（有 setter，Hook 可改变行为）

| 事件 | 触发时机 | 可修改内容 |
|------|---------|-----------|
| `PreCallEvent` | Agent.call() 入口 | 输入消息列表 |
| `PreReasoningEvent` | LLM 调用前 | 消息列表、GenerateOptions |
| `PostReasoningEvent` | LLM 返回后 | 推理消息、stopAgent()、gotoReasoning() |
| `PreActingEvent` | 工具执行前 | ToolUseBlock 参数 |
| `PostActingEvent` | 单个工具执行后 | 工具结果、stopAgent() |
| `PreSummaryEvent` | 摘要生成前 | 消息列表、GenerateOptions |
| `PostSummaryEvent` | 摘要生成后 | 摘要消息 |
| `PostCallEvent` | Agent.call() 出口 | 最终消息 |

### 通知事件（只读）

| 事件 | 触发时机 | 内容 |
|------|---------|------|
| `ReasoningChunkEvent` | LLM 流式输出每个 chunk | 增量文本 |
| `ActingChunkEvent` | 工具流式输出 | 工具中间结果 |
| `ErrorEvent` | 执行出错 | 异常信息 |

## 事件生命周期

```
call() 入口
  │
  ├── PreCallEvent          ← Hook 可修改输入消息
  │
  ├── 循环开始 ─────────────────────────────────────┐
  │   │                                             │
  │   ├── PreReasoningEvent  ← Hook 可修改消息/参数  │
  │   ├── model.stream()                            │
  │   │   └── ReasoningChunkEvent × N  ← 只读      │
  │   ├── PostReasoningEvent ← Hook 可修改/停止/重试 │
  │   │                                             │
  │   ├── PreActingEvent     ← Hook 可修改工具参数   │
  │   ├── toolkit.callTools()                       │
  │   │   └── ActingChunkEvent × N     ← 只读      │
  │   ├── PostActingEvent    ← Hook 可修改/停止      │
  │   │                                             │
  │   └── 继续循环 ─────────────────────────────────┘
  │
  ├── PostCallEvent          ← Hook 可修改最终输出
  │
  └── ErrorEvent             ← 如果出错
```

## 优先级

```java
default int priority() { return 100; }
```

- 0-50: 关键系统 Hook（认证、安全）
- 51-100: 高优先级（校验、预处理）
- 101-500: 普通业务逻辑
- 501-1000: 低优先级（日志、监控）

多个 Hook 按优先级排序依次执行，前一个的输出是后一个的输入。

## 核心 Hook 使用场景

### 场景 1：HITL 人工审批

```java
Hook hitlHook = new Hook() {
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PostReasoningEvent e -> {
                // LLM 想调用危险工具？暂停等人确认
                if (isDangerous(e.getReasoningMessage())) {
                    e.stopAgent();  // 停止循环
                }
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
};
```

**对比 Pokkit**：Pokkit 在工具执行前检查权限（PermissionService），是过程式代码。AgentScope 通过 Hook 实现，不修改核心循环。

### 场景 2：RAG 上下文注入

```java
// GenericRAGHook（内置）
case PreReasoningEvent e -> {
    String context = knowledge.retrieve(lastUserMsg).block();
    List<Msg> msgs = new ArrayList<>(e.getInputMessages());
    msgs.add(0, Msg.builder()
        .role(MsgRole.SYSTEM)
        .content(TextBlock.builder().text("参考资料：\n" + context).build())
        .build());
    e.setInputMessages(msgs);
    yield Mono.just(e);
}
```

### 场景 3：长期记忆自动录入

```java
// StaticLongTermMemoryHook（内置）
case PreReasoningEvent e -> {
    // 检索相关记忆，注入到消息中
    String memories = ltm.retrieve(lastMsg).block();
    // 注入...
}
case PostCallEvent e -> {
    // 自动记录对话到长期记忆
    ltm.record(recentMessages).subscribe();
    yield Mono.just(e);
}
```

### 场景 4：结构化输出流程控制

```java
// StructuredOutputHook（内置）
case PostReasoningEvent e -> {
    // LLM 调用了 generate_response 工具
    // 验证输出是否符合 Schema
    if (!valid) {
        // 注入错误消息，要求重试
        e.gotoReasoning(List.of(errorMsg));
    }
    yield Mono.just(e);
}
```

`gotoReasoning()` 让循环回到推理阶段重试，**不增加 iter 计数**。

## 内置 Hook 列表

| Hook | 功能 |
|------|------|
| `SkillHook` | 注入 Agent 技能描述 |
| `StaticLongTermMemoryHook` | 自动录入/检索长期记忆 |
| `GenericRAGHook` | RAG 上下文自动注入 |
| `PendingToolRecoveryHook` | 恢复中断的工具调用 |
| `GracefulShutdownHook` | 优雅关闭时绑定 Session |
| `StructuredOutputHook` | 结构化输出验证和重试 |
| `StreamingHook` | 捕获事件到 Flux<Event>（stream() 动态创建） |

## 动态 Hook（流式场景）

`stream()` 调用时动态创建 `StreamingHook`：

```java
Flux<Event> createEventStream(options, callSupplier) {
    return Flux.create(sink -> {
        StreamingHook hook = new StreamingHook(sink, options);
        addHook(hook);                    // 临时加入
        callSupplier.get()
            .doFinally(signal -> hooks.remove(hook))  // 完成后移除
            .subscribe(...);
    });
}
```

这意味着 Hook 列表在运行时是**可变的**——这是有意为之，但也意味着 Agent 实例**不支持并发执行**。

## 与 Pokkit 的对比

| 维度 | AgentScope | Pokkit |
|------|-----------|--------|
| 扩展机制 | Hook 事件系统 | 无（硬编码） |
| 添加日志 | 挂 Hook | 改 AgenticLoop |
| 添加 RAG | 挂 Hook | 改 AgenticLoop |
| 添加审批 | 挂 Hook (PostReasoningEvent) | PermissionService（过程式） |
| 结构化输出 | Hook + gotoReasoning | 不支持 |
| HITL | Hook + stopAgent() | 不支持 |

Hook 系统是 AgentScope 架构的灵魂。如果 Pokkit 要演进到生产级，引入 Hook 应该是最优先的事。
