# 07 — 记忆与遗忘：Compaction 系统

## 问题：有限的上下文窗口

LLM 有 token 上限（如 Claude 200K、GPT-4 128K）。当对话历史超过上限时，Agent 面临两个选择：

1. **截断** — 丢弃旧消息（简单但丢失信息）
2. **压缩** — 摘要旧消息（复杂但保留语义）

OpenCode 选择了后者，实现了一个**智能记忆管理系统**。

## Compaction 触发机制

**源码**: `packages/opencode/src/session/overflow.ts`

### 溢出检测

```typescript
function isOverflow(tokens: number, model: Model): boolean {
  const reserved = config.compaction.reserved ?? Math.min(20_000, model.limit.output)
  const usable = model.limit.input - reserved
  return tokens > usable
}
```

**reserved** 是给输出留的 buffer（默认 20K token），确保模型有足够空间回复。

### 触发时机

溢出检测发生在两个地方：

1. **Processor 的 finish-step 事件** — 每个 step 结束时检查
2. **runLoop 的循环开始** — 每次迭代开始前检查

检测到溢出 → `needsCompaction = true` → Processor 返回 `"compact"` → runLoop 执行压缩。

## Compaction 执行流程

**源码**: `packages/opencode/src/session/compaction.ts`

```
溢出检测
    │
    ▼
创建压缩消息 (summary: true)
    │
    ▼
调用 LLM（使用 compaction Agent，无工具）
    │  System Prompt: PROMPT_COMPACTION
    │  输入: 完整的对话历史
    │
    ▼
LLM 生成摘要，关注:
    ├─ 用户的原始目标
    ├─ 给出的指令和约束
    ├─ 重要发现
    ├─ 已完成的工作
    ├─ 相关文件列表
    └─ 当前进展
    │
    ▼
摘要写入 CompactionPart
    │
    ▼
下一次 runLoop:
    MessageV2.filterCompacted() 跳过 CompactionPart 之前的消息
    Agent 只看到: [摘要] + [最近消息]
```

## Pruning：精细化的遗忘

Compaction 之外还有 **Pruning** — 选择性地清除旧的工具输出：

**源码**: `packages/opencode/src/session/compaction.ts` → `prune()`

```
从后向前遍历所有已完成的 ToolPart
    │
    ├─ 保护最近 2 轮对话
    ├─ 保护最近 40K token 的工具输出
    ├─ 保护特定工具（如 "skill"）的输出
    │
    └─ 对于超出保护范围的旧 ToolPart:
         ├─ 标记 time.compacted = Date.now()
         └─ 在发送给 LLM 时，输出被替换为占位文本
```

**Pruning 的触发条件**：只有当被压缩的输出 > 20K token 时才执行（否则节省的空间不值得）。

## 记忆的层次结构

```
┌──────────────────────────────────────────────────┐
│                 完整消息历史                        │
│  (存在 SQLite 中，永不丢失)                        │
├──────────────────────────────────────────────────┤
│                                                  │
│  ┌─────────────┐                                 │
│  │ Compaction   │  ← Agent 看到的"长期记忆"       │
│  │ 摘要         │     (对话精华的浓缩)             │
│  └─────────────┘                                 │
│                                                  │
│  ┌─────────────┐                                 │
│  │ Pruned 工具  │  ← 输出被清除但调用记录保留       │
│  │ 调用记录     │     (Agent 知道"我做过这个"       │
│  └─────────────┘      但看不到具体输出)            │
│                                                  │
│  ┌─────────────┐                                 │
│  │ 最近消息     │  ← Agent 的"短期记忆"            │
│  │ (完整保留)   │     (最近 2 轮 + 40K token)     │
│  └─────────────┘                                 │
│                                                  │
└──────────────────────────────────────────────────┘
```

## Compaction Agent 的 Prompt 设计

Compaction Agent 使用专门的 `PROMPT_COMPACTION`，要求它输出结构化摘要：

```
你是一个对话摘要专家。请总结以下对话，保留:
1. 用户的原始目标和所有约束
2. 重要发现和决策
3. 已完成的工作和修改的文件
4. 当前进展和下一步计划
5. 任何需要记住的上下文

格式要求:
- 具体的文件名和路径
- 关键的代码变更
- 未完成的任务
```

**为什么不直接截断？** 因为 Agent 的行为依赖于理解**为什么走到了当前这一步**。截断会丢失因果链，导致 Agent 在后续迭代中做出矛盾的决策。

## 多次压缩

对话可以被压缩**多次**。每次压缩后，如果对话继续增长再次溢出，会触发新一轮压缩。新的 CompactionPart 包含旧摘要 + 新对话的综合摘要。

```
[原始对话 1-50] → Compaction A (摘要 1-50)
[Compaction A + 对话 51-100] → Compaction B (摘要 1-100)
[Compaction B + 对话 101-150] → Compaction C (摘要 1-150)
```

信息逐渐"蒸馏"——越旧的内容越抽象，越新的内容越具体。
