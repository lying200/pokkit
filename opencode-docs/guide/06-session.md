# 06 — Session 与消息处理

## 会话模型

Session 是 OpenCode 的核心概念，代表一次完整的人机交互会话。

### 消息结构 (MessageV2)

消息采用 "Part" 模型，一条消息由多个 Part 组成：

```typescript
// 用户消息
MessageV2.User {
  role: "user"
  parts: [
    TextPart { type: "text", content: string }
    FilePart { type: "file", url: string, mime: string, source?: ... }
  ]
}

// 助手消息
MessageV2.Assistant {
  role: "assistant"
  parts: [
    TextPart      { type: "text", content: string }
    ReasoningPart { type: "reasoning", content: string }
    ToolPart      { type: "tool", id, name, input, state, output?, error? }
    PatchPart     { type: "patch", file, diff, operation }
    StepPart      { type: "step", snapshot }
  ]
}
```

### ToolPart 状态机

```
pending → running → completed
                  → error
```

## 流式处理器 (SessionProcessor)

`SessionProcessor` 是消息处理的核心，负责处理 LLM 的流式响应：

### 事件类型

| 事件 | 对应 Part | 说明 |
|------|-----------|------|
| `text-start/delta/end` | TextPart | 文本生成 |
| `reasoning-start/delta/end` | ReasoningPart | 推理过程 (Extended Thinking) |
| `tool-input-start/end` | — | 工具输入捕获 |
| `tool-call` | ToolPart | 工具调用开始 |
| `tool-result` | ToolPart | 工具执行完成 |
| `tool-error` | ToolPart | 工具执行失败 |
| `start/finish-step` | StepPart | 步骤边界 |
| `error` | — | 流级别错误 |

### 处理流程

```
LLM Stream Event
    │
    ▼
SessionProcessor.handleEvent()
    │
    ├─ 更新当前 Part (text/reasoning delta 追加)
    ├─ 创建新 Part (tool-call, step)
    ├─ 执行工具 (tool-call → Tool.execute() → tool-result)
    ├─ Snapshot 跟踪 (文件变更 → PatchPart)
    │
    ▼
Session.updateMessage()  → 持久化到 SQLite
    │
    ▼
Bus.publish()  → 通知订阅者 (TUI 更新等)
```

## 消息压缩 (Compaction)

当对话超出 token 上限时，系统会自动进行消息压缩：

1. 检测到 token overflow
2. 调用 `compaction` Agent 对历史消息进行摘要
3. 用摘要替换原始消息，释放 token 空间
4. 保留最近的消息不压缩

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/session/schema.ts` | SessionID, MessageID 等类型 |
| `src/session/message-v2.ts` | 消息和 Part 的完整类型定义 |
| `src/session/processor.ts` | 流式处理器核心逻辑 |
| `src/session/llm.ts` | LLM 流请求构建 |
| `src/session/` | 会话模块全部 |
