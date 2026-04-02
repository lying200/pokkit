# 01 — Agent 执行模型

## 核心认知：Agent 不是进程

理解 OpenCode Agent 系统的第一步，是打破一个常见的心智模型：

> **Agent 不是独立运行的进程或线程，而是一组配置（权限、模型、提示词）+ 一个 while(true) 循环。**

```
Agent = 配置对象 (Agent.Info)
      + Session (消息历史 + 状态)
      + runLoop() (驱动循环)
```

Agent 的"智能"来自 LLM，Agent 的"行动"来自 Tool，Agent 的"边界"来自 Permission。OpenCode 做的事情是**编排**这三者。

## 全局视角

```
                    ┌─────────────────────────────┐
                    │        Agent.Info            │
                    │  name, model, prompt,        │
                    │  permission, temperature     │
                    └──────────┬──────────────────┘
                               │ 配置注入
                               ▼
┌──────────┐    HTTP     ┌────────────┐     Effect      ┌─────────────┐
│  Client  │ ──────────► │   Server   │ ──────────────► │  runLoop()  │
│ TUI/Web  │ ◄────────── │   (Hono)   │                 │  while(true)│
└──────────┘   Stream    └────────────┘                 └──────┬──────┘
                                                               │
                              ┌─────────────────────────────────┤
                              │                                 │
                              ▼                                 ▼
                    ┌──────────────────┐              ┌─────────────────┐
                    │   LLM.stream()   │              │   Tool.execute() │
                    │   (Vercel AI SDK)│              │   (40+ 工具)     │
                    └──────────────────┘              └─────────────────┘
```

## 执行模型的三个层次

### 第一层：Runner — 会话级串行化

**源码**: `packages/opencode/src/effect/runner.ts`

每个 Session 有且仅有一个 Runner。Runner 确保：
- **同一时刻只有一个 runLoop 在执行**（串行化）
- 后续请求排队等待（通过 Deferred）
- 支持取消（cancel → 中断当前 Fiber）

Runner 的状态机：

```
Idle ──► Running ──► Idle
  │                    ▲
  ▼                    │
Shell ──► ShellThenRun ┘
```

这意味着：**一个 Session 不会出现两个 Agent 同时思考的情况。** 并发发生在 Session 之间（子 Agent 在独立 Session 中运行）。

### 第二层：runLoop — 思考-行动循环

**源码**: `packages/opencode/src/session/prompt.ts` → `runLoop()`

这是 Agent 的心跳。每一次循环迭代 = Agent 的一个"回合"：

```
while (true) {
  step++

  // 1. 加载历史，判断是否该退出
  // 2. 创建新的 Assistant 消息
  // 3. 调用 LLM（带工具）
  // 4. 处理结果：继续？压缩？停止？
}
```

退出条件：
- LLM 回复了最终答案（finish reason ≠ "tool-calls"）
- 达到 maxSteps 上限
- 用户取消 (abort)
- 权限被拒绝
- 需要压缩 (overflow)

### 第三层：Processor — 流式事件处理

**源码**: `packages/opencode/src/session/processor.ts`

在一次 LLM 调用内部，Processor 逐事件处理流式响应：

```
LLM Stream Events
    │
    ├── text-delta → 追加到 TextPart
    ├── tool-call → 创建 ToolPart → 执行工具 → 写入结果
    ├── reasoning-delta → 追加到 ReasoningPart
    ├── finish-step → 计算 token/cost，检测 overflow
    └── error → 标记错误，可能终止
```

**关键洞察**：工具执行发生在流式处理的**内部**，而不是在 LLM 调用结束后。这使得 Vercel AI SDK 的 multi-step 能力得以发挥——一次 `streamText` 调用可以包含多轮 tool-use。

## Agent.Info 的本质

Agent 配置看似简单，但它定义了 Agent 的全部身份：

```typescript
{
  name: "build",                    // 身份标识
  mode: "primary",                  // 可作为主 Agent
  prompt: undefined,                // 使用默认 provider prompt
  model: undefined,                 // 使用默认模型
  permission: {                     // ← 这才是 Agent 的真正边界
    "read": { "*": "allow" },
    "edit": { "*": "allow" },
    "bash": { "*": "ask" },
    "task": { "*": "allow" },
    ...
  },
  steps: undefined,                 // 无限迭代
  temperature: undefined,           // 使用模型默认
}
```

**哲学**：Agent 不通过"能做什么"来定义自己，而是通过"被允许做什么"。同一个 LLM + 不同的权限配置 = 完全不同的 Agent 行为。

## 内置 Agent 的设计意图

| Agent | 设计意图 | 关键约束 |
|-------|---------|---------|
| `build` | 全能执行者，能读、写、运行 | bash 需要 ask |
| `plan` | 只看不动手，分析和规划 | edit/write/bash 全部 deny |
| `explore` | 快速搜索，轻量级 | 只有 read/glob/grep/bash/webfetch |
| `general` | 通用子任务执行 | 继承父 Agent 权限子集 |
| `compaction` | 内部摘要生成 | 无工具，hidden |
| `title` | 生成会话标题 | 无工具，hidden |
| `summary` | 生成会话摘要 | 无工具，hidden |
