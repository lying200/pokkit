# 03 — LLM 流式处理引擎

## 架构概述

Agent 的每一次"思考+行动"由两个紧密配合的组件驱动：

```
┌──────────────────┐         ┌──────────────────────────┐
│   LLM.stream()   │  事件流  │  SessionProcessor.Handle │
│   (构建请求,      │ ──────► │  (处理事件, 执行工具,     │
│    发起流)        │         │   更新消息, 检测溢出)     │
└──────────────────┘         └──────────────────────────┘
```

## LLM.stream() — 请求构建

**源码**: `packages/opencode/src/session/llm.ts`

### System Prompt 组装

System Prompt 不是一个字符串，而是一个**数组**——多段内容拼接：

```
┌─────────────────────────────────────────┐
│ 1. Provider 特定 Prompt                  │
│    (Claude/GPT/Gemini 各有不同基础 Prompt) │
├─────────────────────────────────────────┤
│ 2. 环境信息                              │
│    - 当前目录、Git 状态                   │
│    - 平台信息、日期                       │
│    - 模型名称和 ID                        │
├─────────────────────────────────────────┤
│ 3. Skills 目录 (如果 Agent 有权限)        │
│    - 可用 Skill 的列表和描述              │
├─────────────────────────────────────────┤
│ 4. 用户 Instructions                     │
│    - AGENTS.md, CLAUDE.md 等文件内容      │
│    - 配置中的 instructions 字段           │
├─────────────────────────────────────────┤
│ 5. Agent 自定义 Prompt (可选)             │
│    - 如果 Agent.prompt 存在，覆盖第 1 层  │
└─────────────────────────────────────────┘
```

Provider 特定 Prompt 的选择逻辑：

| 模型匹配 | Prompt 模板 | 设计意图 |
|----------|------------|---------|
| `gpt-4, o1, o3` | PROMPT_BEAST | 高级推理优化 |
| `gpt-*` (通用) | PROMPT_GPT / PROMPT_CODEX | GPT 系列通用 |
| `claude*` | PROMPT_ANTHROPIC | Claude 特化 |
| `gemini-*` | PROMPT_GEMINI | Gemini 特化 |
| 其他 | PROMPT_DEFAULT | 通用模板 |

### Plugin Hook 链

在发出请求前，Plugin 有三次介入机会：

```
1. experimental.chat.system.transform → 修改 System Prompt
2. chat.params → 覆盖 temperature/topP/模型选项
3. chat.headers → 添加自定义 HTTP Header
```

### streamText 调用

最终调用 Vercel AI SDK 的 `streamText()`：

```typescript
streamText({
  model: wrappedModel,        // Provider SDK 实例
  system: systemPrompts,      // System Prompt 数组
  messages: modelMessages,    // 对话历史 (已转换为模型格式)
  tools: resolvedTools,       // 工具定义 (含 execute 函数)
  temperature,                // 温度
  maxTokens,                  // 最大输出 token
  maxSteps: 50,              // AI SDK 内部的 multi-step 上限
  abortSignal,               // 取消信号
  ...providerOptions         // Provider 特定选项
})
```

**关键**：`maxSteps: 50` 是 Vercel AI SDK 级别的循环上限，与 runLoop 的 step 是**不同层次**的循环。AI SDK 的 step = 一次 tool-use round-trip；runLoop 的 step = 一次完整的 LLM 调用。

## SessionProcessor — 事件驱动的状态机

**源码**: `packages/opencode/src/session/processor.ts`

### 事件类型与处理

```
事件流 ──────────────────────────────────────────────────►
  │
  ├─ start-step
  │    └→ Snapshot.track() → 记录当前文件状态
  │
  ├─ text-start
  │    └→ 创建新 TextPart
  │
  ├─ text-delta
  │    └→ 追加文本到当前 TextPart
  │    └→ 实时写入数据库
  │
  ├─ text-end
  │    └→ 标记 TextPart 完成
  │
  ├─ reasoning-start / delta / end
  │    └→ 同 text，但写入 ReasoningPart (Extended Thinking)
  │
  ├─ tool-input-start
  │    └→ 创建 ToolPart (state: pending)
  │
  ├─ tool-call
  │    ├→ 更新 ToolPart (state: running, input: 解析后的参数)
  │    ├→ Doom Loop 检测 (同工具+同输入连续3次？)
  │    └→ **内联执行工具** → tool.execute(args, context)
  │
  ├─ tool-result
  │    └→ 更新 ToolPart (state: completed, output: 工具输出)
  │
  ├─ tool-error
  │    └→ 更新 ToolPart (state: error, error: 错误信息)
  │
  ├─ finish-step
  │    ├→ Snapshot.patch() → 计算文件变更 → 创建 PatchPart
  │    ├→ 计算 token 使用量和成本
  │    └→ **溢出检测** → 标记 needsCompaction
  │
  └─ error
       └→ 记录错误，可能终止流
```

### 工具内联执行的含义

这是理解整个系统的关键点：

```
LLM 流：  text... text... tool-call... [等待工具执行] ...tool-result... text...
                                ↑                              ↑
                          工具开始执行                      工具执行完成
                          (可能耗时数秒)                   LLM 继续生成
```

工具执行**阻塞了流的处理**，但这是有意为之——LLM 需要看到工具结果才能继续思考。Vercel AI SDK 在内部处理了"调用工具 → 等待结果 → 把结果喂回 LLM → 继续生成"的循环。

### Processor 的返回值

```typescript
process() → "stop" | "continue" | "compact"
```

| 返回值 | 含义 | runLoop 的反应 |
|--------|------|---------------|
| `"stop"` | 完成了，或被中断 | break |
| `"continue"` | LLM 说完了但可能还需要继续 | 下一次迭代 |
| `"compact"` | token 溢出，需要压缩 | 创建 compaction，continue |

### 实时持久化

每个 Part 的每次更新都通过 `Session.updateMessage()` 写入 SQLite：

```
text-delta "Hello" → DB write
text-delta " world" → DB write
tool-call {name: "read"} → DB write
tool-result {output: "..."} → DB write
```

这意味着：
- **TUI 可以实时显示**（通过 Bus 订阅 Part 更新事件）
- **断电不丢失**（SQLite WAL 模式保证原子写入）
- **可以被 ACP 实时转发**（Agent Control Protocol 订阅同样的事件）

## 消息格式转换

### MessageV2 → Model Messages

在发送给 LLM 之前，内部的 MessageV2 格式需要转换为 AI SDK 的 ModelMessage 格式：

```
MessageV2.User
  TextPart → { type: "text", text: "..." }
  FilePart → { type: "image", image: base64 } 或 { type: "file", ... }

MessageV2.Assistant
  TextPart → { type: "text", text: "..." }
  ToolPart → { type: "tool-call", toolCallId, toolName, args }
           + { type: "tool-result", toolCallId, result }
  ReasoningPart → (provider 特定处理)
  CompactionPart → { type: "text", text: "摘要内容" }
```

**被压缩的工具输出**会被替换为占位文本，节省 token。
