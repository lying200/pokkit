# 05 — 多 Agent 编排

## 编排模型：Session 树

OpenCode 的多 Agent 不是"对等协作"，而是**层级委派**：

```
用户消息
    │
    ▼
Build Agent (主 Session)
    │
    ├──► Task("explore", "搜索所有 TODO")
    │         │
    │         ▼
    │    Explore Agent (子 Session A)
    │         └→ 执行 grep/read → 返回结果
    │
    ├──► Task("general", "分析这个报错")
    │         │
    │         ▼
    │    General Agent (子 Session B)
    │         └→ 执行分析 → 返回结果
    │
    └──► 基于子任务结果，继续工作
```

**核心机制：Task 工具**

Task 不是一个特殊的系统调用——它就是一个普通的 Tool，LLM 自主决定是否调用它。

## Task 工具的内部实现

**源码**: `packages/opencode/src/tool/task.ts`

### 创建子 Session

```typescript
// 1. 过滤可用的子 Agent（排除 primary 模式）
const agents = Agent.list().filter(a => a.mode !== "primary")

// 2. 权限检查：父 Agent 能否委派给这个子 Agent？
const accessible = agents.filter(a =>
  Permission.evaluate("task", a.name, caller.permission).action !== "deny"
)

// 3. 创建子 Session
const childSession = Session.create({
  parentID: currentSessionID,  // 建立父子关系
  agent: subagentType,
  // 权限继承 + 约束
})
```

### 权限隔离

子 Agent 的权限是**父 Agent 权限的子集**：

```
父 Agent 权限: { edit: allow, bash: ask, task: allow, todowrite: allow }
    │
    ▼ 继承 + 约束
子 Agent 权限: { edit: allow, bash: ask, task: deny, todowrite: deny }
                                          ↑              ↑
                                    可能被禁止        可能被禁止
                                    (防止无限嵌套)    (子 Agent 不管任务)
```

**为什么禁止子 Agent 的 task 权限？** 防止 Agent 无限嵌套委派——A 委派给 B，B 又委派给 C...

### 执行与返回

```typescript
// 4. 在子 Session 中执行 prompt
const result = await SessionPrompt.prompt({
  sessionID: childSession.id,
  input: { prompt: taskPrompt },
  agent: subagentType,
})

// 5. 收集子 Agent 的最终输出
// 子 Agent 的最后一条文本回复 = Task 工具的 output

// 6. 返回给父 Agent
return {
  title: `Task: ${description}`,
  output: `<task_result id="${taskID}">\n${agentOutput}\n</task_result>`,
}
```

父 Agent 看到的是一个普通的工具调用结果——它不需要知道子 Agent 内部调用了多少工具、思考了多少步。

### 恢复子任务

Task 工具支持 `task_id` 参数——传入之前的子 Session ID 可以**恢复**而非重新创建：

```typescript
// 父 Agent 可以对同一个子 Agent 说 "继续之前的工作"
Task({
  task_id: "previous-session-id",
  prompt: "基于之前的分析，再看看 error handling"
})
```

这使得跨迭代的子任务协作成为可能。

## Agent 之间的信息流

```
┌─────────────────────────────────────────────┐
│              父 Agent (Build)                │
│                                             │
│  消息历史:                                   │
│    User: "重构 auth 模块"                    │
│    Assistant: [思考...] → task(explore)      │
│    Tool Result: <task_result>               │
│      "发现 auth.ts, middleware.ts, types.ts  │
│       使用了旧的 token 验证逻辑..."          │
│    </task_result>                           │
│    Assistant: [基于发现继续工作...]           │
│                                             │
└──────────────┬──────────────────────────────┘
               │
               │ Task 工具创建子 Session
               ▼
┌─────────────────────────────────────────────┐
│              子 Agent (Explore)              │
│                                             │
│  消息历史 (独立的):                           │
│    User: "找到所有使用旧 auth API 的地方"     │
│    Assistant: → grep("旧API模式")            │
│    Tool Result: 匹配了 5 个文件               │
│    Assistant: → read(每个文件)               │
│    Tool Result: 文件内容                     │
│    Assistant: "发现 auth.ts..."  ← 这段返回  │
│                                             │
└─────────────────────────────────────────────┘
```

**关键点**：子 Agent 有**独立的消息历史**。它不能看到父 Agent 的完整上下文，只能看到 Task 工具传入的 prompt。这是有意的设计——保持子任务的聚焦性。

## Batch 工具：并行执行

**源码**: `packages/opencode/src/tool/batch.ts`

Batch 不是多 Agent，而是**同一个 Agent 并行调用多个工具**：

```typescript
// LLM 可以一次请求执行多个工具
Batch({
  tool_calls: [
    { tool: "read", input: { file_path: "a.ts" } },
    { tool: "read", input: { file_path: "b.ts" } },
    { tool: "read", input: { file_path: "c.ts" } },
  ]
})

// 内部: Promise.all([read(a), read(b), read(c)])
// 上限: 25 个并行调用
// 禁止嵌套: batch 不能调用 batch
```

## 编排模式对比

| 模式 | 机制 | 并发 | Session | 适用场景 |
|------|------|------|---------|---------|
| 单 Agent 循环 | runLoop | 串行 | 同一个 | 大多数任务 |
| Task 委派 | 子 Session | 串行（等待子 Agent 完成） | 新 Session | 搜索、分析、子任务 |
| Batch 并行 | Promise.all | 并行（工具级） | 同一个 | 批量文件读取 |

**注意**：Task 工具执行时，父 Agent 是**阻塞等待**的。目前不支持"发起子任务然后继续干别的"。子任务完成后结果才返回给父 Agent。
