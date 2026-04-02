# 02 — Agentic Loop 详解

## runLoop 逐行解剖

**源码**: `packages/opencode/src/session/prompt.ts` → `runLoop()` (~line 1331)

这是整个 Agent 系统最核心的 200 行代码。下面逐步拆解每一次循环迭代到底发生了什么。

## 一次完整迭代

```
┌─────────────────────────────────────────────────────────┐
│                    runLoop() 一次迭代                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ① 加载消息历史 (filterCompacted)                        │
│       │                                                 │
│  ② 扫描：找到 lastUser, lastAssistant, lastFinished     │
│       │                                                 │
│  ③ 退出判断                                              │
│       │  lastAssistant 已完成 & 在 lastUser 之后？       │
│       │  → YES: break                                   │
│       │                                                 │
│  ④ 获取 Agent 配置                                       │
│       │                                                 │
│  ⑤ 处理特殊情况                                          │
│       ├─ 有待处理的 subtask？ → handleSubtask()          │
│       ├─ 有待处理的 compaction？ → 处理压缩              │
│       └─ token overflow？ → 创建 compaction              │
│       │                                                 │
│  ⑥ 创建新的 Assistant 消息                               │
│       │                                                 │
│  ⑦ 创建 Processor (Handle)                              │
│       │                                                 │
│  ⑧ 解析可用工具 (resolveTools)                           │
│       │  Agent 权限过滤                                  │
│       │  MCP 工具合并                                    │
│       │  模型特定工具替换                                 │
│       │                                                 │
│  ⑨ 构建 System Prompt                                   │
│       │  Provider 特定 Prompt                            │
│       │  + 环境信息                                      │
│       │  + Skills 目录                                   │
│       │  + 用户 Instructions                             │
│       │                                                 │
│  ⑩ 调用 Processor.process()                             │
│       │  → LLM.stream()                                 │
│       │  → 处理每个流事件                                │
│       │  → 工具内联执行                                  │
│       │                                                 │
│  ⑪ 判断结果                                              │
│       ├─ "stop" → break                                 │
│       ├─ "compact" → 创建压缩消息，continue              │
│       └─ "continue" → 下一次迭代                         │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 关键步骤详解

### ① 加载消息历史

```typescript
const msgs = await MessageV2.filterCompacted(
  MessageV2.stream(sessionID)
)
```

`filterCompacted` 做了什么：
- 如果存在 CompactionPart，跳过它之前的所有旧消息
- 如果工具输出被标记为 `compacted`，隐藏输出内容
- 结果：Agent 只看到**压缩摘要 + 最近消息**，而不是完整历史

### ③ 退出判断的精确逻辑

```
lastAssistant 存在
  AND lastAssistant.finish 不是 "tool-calls"
  AND lastUser.id < lastAssistant.id  (用户没有新消息)
→ 循环结束
```

换句话说：如果 LLM 的最后一条回复不需要继续调用工具，且用户没有发新消息，那就结束。

### ⑤ 特殊情况处理

这一步体现了循环的**自我调节能力**：

**Subtask 处理**：如果上一轮创建了 SubtaskPart（子 Agent 任务），本轮会先处理它而不是调用 LLM。

**Compaction 处理**：如果上一轮标记了需要压缩，本轮先执行压缩然后 continue。

**Overflow 检测**：如果 token 超限，主动创建压缩而不是等到 LLM 调用失败。

### ⑧ 工具解析 (resolveTools)

这里发生了三层过滤：

```
全部工具 (Registry)
    │
    ▼
模型过滤：某些工具只对特定 Provider 可用
    │  (如 codesearch 只给 opencode provider)
    │  (如 apply_patch 只给 GPT 模型)
    │
    ▼
Agent 权限过滤：Permission.disabled(toolNames, agent.permission)
    │  (plan agent 看不到 edit/write/bash)
    │
    ▼
工具初始化：tool.init(agent)
    │  (工具可以根据 Agent 调整自己的描述)
    │
    ▼
权限包装：每次 execute 前检查 Permission.ask()
    └→ 最终工具集
```

### ⑩ Processor.process() 内部

这是真正的"思考+行动"发生的地方。详见 [03-streaming-engine.md](03-streaming-engine.md)。

## Step 计数与 MaxSteps

```typescript
const maxSteps = agent.steps ?? Infinity
const isLastStep = step >= maxSteps
```

如果是最后一步，会注入一段 `MAX_STEPS` 提示：

> "你已经到达最大步数限制。请在这一步完成任务或总结当前进展。"

这是一个**软限制**——它通过 Prompt 引导 LLM 结束，而不是强制中断。

## 循环的不变式 (Invariants)

理解这些不变式有助于理解系统的正确性保证：

1. **每次迭代创建恰好一条 Assistant 消息** — 不会出现一次迭代产生多条回复
2. **工具执行在 Processor 内部完成** — runLoop 不直接调用工具
3. **消息实时持久化** — 每个 Part 变更都写入 SQLite，断电不丢失
4. **循环终将结束** — 通过 maxSteps 或 LLM 自行停止或 token overflow
5. **压缩不丢失语义** — CompactionPart 包含完整的上下文摘要

## 一个具体例子

用户说："分析 src/auth.ts 并重构 token 处理逻辑"

```
迭代 1:
  LLM 思考 → "我需要先看看这个文件"
  → 调用 read 工具读取 src/auth.ts
  → 调用 grep 工具搜索相关引用
  → finish reason: "tool-calls" (还没说完)

迭代 2:
  LLM 拿到文件内容和搜索结果
  → 思考 → "我理解了，需要这样重构"
  → 调用 edit 工具修改 src/auth.ts
  → 调用 bash 工具运行测试
  → finish reason: "tool-calls"

迭代 3:
  LLM 看到编辑结果和测试输出
  → "重构完成，测试通过。以下是变更说明..."
  → finish reason: "stop"

循环结束，返回最终消息。
```
