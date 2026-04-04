# Pokkit 第八步：Hook 事件系统

> **对应 git tag: `v0.8-hook`**

## 为什么做这个

当前 AgenticLoop 的所有逻辑硬编码在 `run()` 方法里：权限检查、日志打印、doom loop 检测……
每加一个横切功能就要改核心循环。这不可扩展。

AgentScope Java 给了一个清晰的答案：**Hook 事件系统**。核心循环只负责 LLM 调用和工具执行，
其余逻辑通过 Hook 注入，不修改循环代码。

## 从 AgentScope 学到了什么

AgentScope 的 Hook 系统有 10+ 种事件、Mono/Flux 异步、优先级排序。
我们只取最小可用集：**4 个同步事件 + 优先级链式执行**。

关键设计决策（与 AgentScope 的差异）：
- **同步而非异步** — Pokkit 的循环是阻塞的，不需要 Mono/Flux
- **可修改事件** — Hook 可以修改事件内容（如修改消息列表、替换工具结果）
- **流程控制** — Hook 可以通过事件的 `stopAgent()` 终止循环、`skipTool()` 跳过工具执行

## 4 个事件

```
AgenticLoop.run() {
    while (true) {
        ① PreReasoningEvent   — LLM 调用前，可修改消息列表
            ↓
        chatModel.stream(messages)
            ↓
        ② PostReasoningEvent  — LLM 返回后，可检查/修改响应
            ↓
        for (toolCall : toolCalls) {
            ③ PreActingEvent   — 工具执行前，可跳过/修改参数
                ↓
            tool.execute()
                ↓
            ④ PostActingEvent  — 工具执行后，可修改结果
        }
    }
}
```

### 事件的能力

| 事件 | 可修改 | 流程控制 |
|------|--------|---------|
| PreReasoningEvent | 消息列表 | stopAgent() |
| PostReasoningEvent | — | stopAgent() |
| PreActingEvent | — | skipTool()（跳过执行，返回自定义结果） |
| PostActingEvent | 工具输出 | stopAgent() |

## 迁移：PermissionService → PermissionHook

当前权限检查硬编码在循环里：
```java
var permResult = permissionService.check(toolCall.name(), argsPreview);
if (permResult == DENIED) { ... }
```

迁移为 `PreActingEvent` Hook：
```java
class PermissionHook implements Hook {
    public void onEvent(HookEvent event) {
        if (event instanceof PreActingEvent e) {
            var result = permissionService.check(e.toolName(), e.argsPreview());
            if (result == DENIED) e.skipTool("Permission denied...");
            else if (result == REJECTED) e.skipTool("User rejected...");
        }
    }
}
```

权限逻辑不变，但从循环中解耦了。

## 代码变化

```
新增:
  hook/Hook.java              — Hook 接口
  hook/HookEvent.java         — 事件基类
  hook/PreReasoningEvent.java — LLM 调用前事件
  hook/PostReasoningEvent.java— LLM 返回后事件
  hook/PreActingEvent.java    — 工具执行前事件
  hook/PostActingEvent.java   — 工具执行后事件
  hook/HookRegistry.java      — Hook 注册表，管理优先级和执行
  hook/PermissionHook.java    — 权限检查 Hook（从 AgenticLoop 迁出）

修改:
  agent/AgenticLoop.java      — 在关键点触发事件，移除硬编码的权限检查
```

## OpenCode 做了但我们简化掉的

### 异步事件流

AgentScope 的 Hook 返回 `Mono<T>`，支持异步操作（如异步调用外部审批系统）。
我们用同步执行，`Hook.onEvent()` 直接修改事件对象。

**生产影响**：无法在 Hook 中做异步 I/O（如调用外部 API 审批），
但 Pokkit 当前的 permission 交互是 Scanner 阻塞读取，同步足够。

### 流式 chunk 事件

AgentScope 有 `ReasoningChunkEvent`（LLM 每个 token）和 `ActingChunkEvent`（工具中间输出），
用于实时监控。我们不支持 chunk 级事件。

**生产影响**：无法在 Hook 层面做流式 token 级处理（如实时翻译、实时审查）。

### 动态 Hook

AgentScope 的 `stream()` 调用时动态创建 `StreamingHook`，调用结束后自动移除。
我们的 Hook 在 Agent 创建时注册，运行期间固定。

**生产影响**：无法按请求动态添加/移除 Hook，灵活性较低。

### gotoReasoning 流程控制

AgentScope 的 `PostReasoningEvent` 支持 `gotoReasoning()`，
让循环回到推理阶段重试（用于结构化输出验证失败时重试）。
我们不支持这种回跳。

**生产影响**：结构化输出验证失败后只能在下一轮循环中重试，多浪费一轮 LLM 调用。
