# 04 — 工具执行与反馈闭环

## 核心洞察

工具不是"Agent 的附属品"——**工具的输出是 Agent 下一轮思考的核心输入**。

这构成了一个反馈闭环：

```
LLM 思考 → 决定调用工具 → 工具执行 → 输出写入消息历史 → LLM 基于输出继续思考
    ▲                                                          │
    └──────────────────────────────────────────────────────────┘
```

这不是"命令模式"（发出指令、接收结果），而是"对话模式"——**工具输出是对话的一部分，和用户消息地位相同。**

## 工具执行的完整生命周期

### 1. LLM 生成工具调用

```
LLM stream: ... "我需要查看这个文件" ...
            tool-call: { name: "read", input: { file_path: "/src/auth.ts" } }
```

### 2. 参数验证

每个工具用 Zod schema 定义输入：

```typescript
// read 工具的参数 schema（简化）
z.object({
  file_path: z.string(),
  offset: z.number().optional(),
  limit: z.number().optional(),
})
```

验证失败 → 返回格式化的错误信息给 LLM → LLM 尝试修正参数。

### 3. 权限检查

```
Permission.evaluate("read", "/src/auth.ts", mergedRuleset)
  │
  ├─ "allow" → 继续执行
  ├─ "deny"  → 返回 DeniedError 给 LLM
  └─ "ask"   → 暂停，等待用户确认
                ├─ 用户选 "once" → 继续
                ├─ 用户选 "always" → 记住规则，继续
                └─ 用户选 "reject" → 返回错误 + 用户反馈给 LLM
```

**"reject" 的妙处**：用户可以附带反馈文字（CorrectedError），这段文字会作为工具错误返回给 LLM。LLM 看到了"用户不让我这样做，因为..."，可以调整策略。

### 4. 工具执行

工具接收 `Tool.Context`——这不只是参数，而是**完整的执行上下文**：

```typescript
{
  sessionID,              // 当前会话
  messageID,              // 当前消息
  agent: "build",         // 调用我的 Agent 是谁
  abort: AbortSignal,     // 取消信号
  messages: [...],        // 完整对话历史（工具可以回顾）
  metadata(data),         // 更新自己的显示标题/元数据
  ask(req),               // 向用户请求权限
}
```

**为什么工具需要对话历史？** 因为某些高级工具（如 Task、Skill）需要理解上下文才能正确执行。

### 5. 输出截断

```
工具原始输出 (可能很长)
    │
    ▼
截断检查: >2000 行 或 >50KB？
    │
    ├─ 未超限 → 原样返回
    │
    └─ 超限 →
         ├─ 完整内容写入临时文件 (~/.opencode/data/truncation/)
         ├─ 返回截断后的预览 + 提示
         └─ 提示内容取决于 Agent 能力：
              ├─ 有 task 权限 → "用 Task 工具让 explore agent 处理"
              └─ 无 task 权限 → "用 Grep 搜索或 Read 分页读取"
```

### 6. 结果写回

工具输出成为 ToolPart 的一部分，写入消息历史。在下一轮 LLM 调用中，这个输出会作为 `tool-result` 出现在对话中。

## 反馈闭环的力量

### 示例：自我修正

```
迭代 1:
  LLM: 调用 bash("npm test")
  Tool: 返回 "Error: Cannot find module 'lodash'"

迭代 2:
  LLM 看到错误输出
  LLM: 调用 bash("npm install lodash")
  Tool: 返回 "added 1 package"
  LLM: 调用 bash("npm test")
  Tool: 返回 "All tests passed"

迭代 3:
  LLM: "测试已通过。我安装了缺失的 lodash 依赖。"
```

Agent 没有被预编程"如果测试失败就安装依赖"——它通过**阅读工具输出**自主决定了下一步。

### 示例：Agent 间信息传递

```
迭代 1:
  Build Agent: 调用 Task(agent: "explore", prompt: "找到所有使用旧 auth API 的地方")

  [Explore Agent 在子 Session 中执行]
    调用 grep("oldAuthAPI")
    调用 read(匹配的文件们)
    返回: "发现 5 个文件使用旧 API: a.ts, b.ts, c.ts, d.ts, e.ts"

  Task 工具返回 explore 的结果给 Build Agent

迭代 2:
  Build Agent 看到搜索结果
  Build Agent: 调用 edit(a.ts), edit(b.ts), ...
```

Explore Agent 的**全部发现**作为 Task 工具的输出，流入 Build Agent 的上下文。

## 工具输出的持久性

工具输出不是一次性的——它被永久存储在 MessageV2 的 ToolPart 中：

```typescript
ToolPart {
  type: "tool",
  tool: "read",
  state: {
    status: "completed",
    input: { file_path: "/src/auth.ts" },
    output: "文件的完整内容...",
    time: { created: ..., completed: ... }
  }
}
```

这意味着：
- 后续的 Compaction 可以智能地选择哪些工具输出保留、哪些可以丢弃
- ACP 客户端可以重放完整的工具调用历史
- 用户可以在 TUI 中查看每个工具调用的详情
