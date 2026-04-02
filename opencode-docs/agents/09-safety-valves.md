# 09 — 安全阀机制

## 为什么需要安全阀

AI Agent 有一个本质风险：**它可能失控**。

- 无限循环调用工具
- 重复犯同一个错误
- 消耗大量 token（= 金钱）
- 修改不该修改的文件

OpenCode 设计了三道安全阀来防止这些问题。

## 第一道：Doom Loop 检测

**源码**: `packages/opencode/src/session/processor.ts`

### 检测逻辑

```typescript
const DOOM_LOOP_THRESHOLD = 3

// 每次 tool-call 事件触发时：
const recentParts = assistantMessage.parts.slice(-DOOM_LOOP_THRESHOLD)

if (
  recentParts.length === DOOM_LOOP_THRESHOLD &&
  recentParts.every(part =>
    part.type === "tool" &&
    part.tool === currentToolName &&
    part.state.status !== "pending" &&
    JSON.stringify(part.state.input) === JSON.stringify(currentInput)
  )
) {
  // DOOM LOOP! 同一个工具被连续调用 3 次，且输入完全相同
  await permission.ask({
    permission: "doom_loop",
    patterns: [currentToolName],
    metadata: { tool: currentToolName, input: currentInput }
  })
}
```

### 为什么是"同输入"检测？

Agent 重复调用同一个工具但**输入不同**是正常的（比如依次读取多个文件）。但如果输入**完全相同**——那意味着 Agent 在重复做同一件事却期待不同结果，这就是 doom loop。

### 用户的选择

检测到 doom loop 后：
- **允许** — Agent 继续（也许用户知道这是合理的）
- **拒绝 + 反馈** — "这个命令有问题，试试 xxx" → Agent 读到反馈，调整策略

## 第二道：MaxSteps 限制

**源码**: `packages/opencode/src/session/prompt.ts` → `runLoop()`

### 机制

```typescript
const maxSteps = agent.steps ?? Infinity  // 默认无限制
let step = 0

while (true) {
  step++
  const isLastStep = step >= maxSteps

  if (isLastStep) {
    // 注入 MAX_STEPS 提示词
    // "你已经到达最大步数限制。请完成任务或总结进展。"
  }

  // ... 正常执行 ...
}
```

### 设计哲学

MaxSteps 是**软限制**而非硬切断：
- 它通过 Prompt 引导 LLM 收尾，而不是直接终止
- LLM 在最后一步仍然可以调用工具（只是被告知"这是最后一步"）
- 如果 LLM 在最后一步仍然返回 "tool-calls"，下一轮的退出检查会终止循环

**为什么默认 Infinity？** 因为大多数场景下，LLM 会自主判断何时结束。MaxSteps 是为特殊场景（如 explore agent 不应该无限搜索）设计的保底机制。

## 第三道：Abort（用户中断）

**源码**: `packages/opencode/src/session/prompt.ts` → `cancel()`

### 触发方式

用户在 TUI 中按 Ctrl+C 或点击取消 → 调用 `SessionPrompt.cancel(sessionID)`

### 中断流程

```
用户触发 abort
    │
    ▼
Runner.cancel()
    │
    ├→ 中断当前 Effect Fiber
    ├→ AbortController.abort()  → AbortSignal 传播
    │
    ▼
Processor.cleanup()
    │
    ├→ 完成当前快照 (不丢失文件变更记录)
    ├→ 完成进行中的 TextPart
    ├→ 标记未完成的 ToolPart 为 "error: Tool execution aborted"
    ├→ 完成 Assistant 消息 (设置 finish reason)
    └→ 设置 Session 状态为 idle
```

### AbortSignal 的传播

```
SessionPrompt.cancel()
    │
    ▼
AbortController.abort()
    │
    ├→ LLM stream 中断 (Vercel AI SDK 监听 AbortSignal)
    ├→ Tool.execute() 中断 (每个工具接收 ctx.abort)
    │    └→ bash 工具会杀掉子进程
    └→ Processor 停止处理事件
```

**关键**：Abort 后消息**不会丢失**。已经生成的文本和已经完成的工具调用都被保存。用户可以看到"Agent 做到哪里了"。

## 三道安全阀的协同

```
Agent 开始执行
    │
    ▼
┌──────────────────────────────────────────┐
│  安全阀 1: 每次工具调用                    │
│  → Doom Loop 检测 (连续3次同工具同输入)    │
│  → 权限检查 (每次执行前)                   │
├──────────────────────────────────────────┤
│  安全阀 2: 每次循环迭代                    │
│  → MaxSteps 检查 (到达上限？)              │
│  → Token overflow 检查 (需要压缩？)        │
├──────────────────────────────────────────┤
│  安全阀 3: 随时                           │
│  → 用户 Abort (Ctrl+C)                   │
│  → 权限拒绝 (用户 reject)                 │
└──────────────────────────────────────────┘
```

## 输出截断：隐形的安全阀

虽然不是"安全阀"名义，但工具输出截断也起到了保护作用：

```
工具输出 > 2000 行 或 > 50KB
    │
    ▼
截断，完整内容写入临时文件
    │
    ▼
Agent 只看到摘要 + "如何获取完整内容"的提示
```

这防止了一个工具输出吃掉整个上下文窗口的情况。

## Token 成本追踪

每个 Step 的 `finish-step` 事件中计算 token 使用量和成本：

```typescript
StepFinishPart {
  cost: number,       // 本次调用的费用
  tokens: {
    input: number,    // 输入 token (含缓存)
    output: number,   // 输出 token
    cached: number,   // 缓存命中 token
    reasoning: number // 推理 token (如果支持)
  }
}
```

虽然 OpenCode 目前没有硬性的费用限制，但这些数据让用户可以监控成本，也为未来实现预算控制提供了基础。
