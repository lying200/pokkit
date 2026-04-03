# Pokkit 第五步：消息压缩 / Compaction

> **对应 git tag: `v0.5-compaction`**

## 为什么做这个

长对话会导致消息历史超过模型的 context window 限制（通常 128K token），
LLM 要么报错，要么丢失早期上下文导致行为异常。

## 从 OpenCode 学到了什么

OpenCode 的 compaction 机制在 `packages/opencode/src/session/compaction.ts`，核心是两层压缩：

### 第一层：Pruning（修剪）

把旧的工具输出替换为占位文本 `"[Old tool result content cleared]"`：
- 从后往前遍历消息历史
- 保护最近 40K token 的工具输出不动
- 更早的工具输出全部替换
- 至少要节省 20K token 才执行

**为什么只替换工具输出？** 因为工具输出通常是最大的 token 消耗者（读文件、bash 输出），
而 LLM 的文本回复和用户消息相对较短。

### 第二层：Summarization（摘要）

Pruning 后还是超限 → 调 LLM 总结旧对话：
- 用一个专门的"compaction agent"（我们复用同一个 ChatModel）
- 生成结构化摘要：目标、发现、已完成、相关文件
- 摘要替换掉旧消息，只保留最近 2 轮用户交互

### Token 估算

OpenCode 用 `text.length / 4` 做线性近似，不精确但快速且够用。
不需要真正的 tokenizer（tiktoken 之类的），因为只是判断"是否该压缩了"。

### 触发时机

每轮 LLM 响应后检查 token 估算值是否超过阈值。

## Pokkit 的实现

提取 OpenCode 的核心逻辑，简化边缘处理。

### 阈值设置

| 常量 | Pokkit | OpenCode | 说明 |
|------|--------|----------|------|
| `TOKEN_LIMIT` | 80K | `model.limit.input - 20K` | 触发压缩 |
| `PRUNE_PROTECT` | 10K | 40K | 保护最近这么多 token |
| `PRUNE_MINIMUM` | 5K | 20K | 至少节省这么多才修剪 |

我们的阈值更小，因为是学习项目，不需要等到真的快溢出才压缩。

### Pruning 实现

```
从后往前遍历 history
  ├─ 跳过最近 2 轮 UserMessage（保护区）
  ├─ 累计 ToolResponseMessage 的 token 数
  ├─ 累计 > PRUNE_PROTECT 后：
  │   └─ 把该条 ToolResponseMessage 替换为包含占位文本的新消息
  └─ 返回节省的 token 数
```

### Summarization 实现

```
1. 提取保护区之前的旧消息
2. 构建摘要请求：
   - SystemMessage: "你是对话摘要器"
   - 旧消息原样传入
   - UserMessage: 摘要 prompt（目标、发现、已完成、文件）
3. chatModel.call()（非流式）
4. 替换 history：
   [UserMessage("以下是之前对话的摘要")] + [AssistantMessage(摘要)] + [保护区消息]
```

### 触发位置

在 `AgenticLoop` 的 while 循环内，每轮构建消息发给 LLM 之前：

```java
compactor.compactIfNeeded(conversationHistory);
```

### 持久化联动

Compaction 会修改内存中的 `history` 列表（替换/删除消息），
Repl 在保存时检测到 `compactor.wasCompacted()` 为 true，
调用 `repo.replaceMessages()` 重写整个 session 的消息（事务内先删后插）。

## 代码结构变化

```
src/main/java/com/pokkit/agent/
├── AgenticLoop.java         # 加入 compaction 检查
└── MessageCompactor.java    # 新增：token 估算 + pruning + summarization
```

## OpenCode 做了但我们简化掉的

以下是 OpenCode 的完整实现中包含、但 Pokkit 为学习目的简化掉的逻辑。
生产环境中这些都很重要，读者在做真实项目时应当考虑补回来。

### 按模型动态调整阈值

OpenCode 的阈值不是硬编码的，而是根据当前模型的 context window 动态计算：
`usable = model.limit.input - reserved`。不同模型（GPT-4o 128K vs Claude 200K vs Gemini 1M）
的触发点不同。我们硬编码 80K 是因为只有一个模型在跑。

**生产影响**：多模型支持时必须动态化，否则小模型溢出、大模型浪费。

### 精确 Token 计数

OpenCode 在 LLM 响应的 `step-finish` 事件中拿到 provider 返回的真实 token 数
（input/output/cache read/write），用这个判断是否溢出。`length/4` 只用在 pruning 估算上。

**生产影响**：`length/4` 对中文文本偏差大（中文 1 字符 ≈ 2-3 token），真实项目应使用 API 返回的 token 计数。

### CompactionPart 与 Part 系统

OpenCode 有完整的 Part 体系（TextPart, ToolPart, ReasoningPart, PatchPart, CompactionPart），
compaction 是一个独立的 Part 类型，记录了 `auto`（是否自动触发）和 `overflow`（是否媒体溢出）标记。
Part 粒度的存储使得 TUI 可以实时更新单个 part，也支持精确回滚。

**生产影响**：没有 Part 系统，我们无法做到消息级别的局部更新和精确撤销。

### 独立的 Compaction Agent

OpenCode 为压缩定义了一个独立的 `compaction` agent，使用低成本模型（如 gpt-4.1-mini），
与主 agent 隔离。这样压缩不消耗主 agent 的高级模型配额。

**生产影响**：复用主 ChatModel 意味着压缩也消耗同样昂贵的 token。

### Plugin 扩展点

OpenCode 的 compaction 有 plugin hook（`experimental.session.compacting`），
允许第三方插件自定义压缩 prompt。比如可以让 plugin 添加特定领域的摘要要求。

**生产影响**：写死的 prompt 无法适配不同项目的需求。

### 多轮递归压缩

OpenCode 支持压缩后再次触发压缩（如果摘要本身还是太长），形成多轮递归。
通过 `summary: true` 标记避免对摘要消息重复压缩。

**生产影响**：极长对话（数百轮）可能一次压缩后仍然超限，需要递归。

### Skill 工具输出保护

OpenCode 的 pruning 会跳过 `skill` 类型的工具输出（`PRUNE_PROTECTED_TOOLS = ["skill"]`），
因为 skill 输出通常是关键上下文，不应被清理。

**生产影响**：盲目清理所有工具输出可能丢失关键的 skill 执行结果。

### 媒体文件剥离

Summarization 时，OpenCode 会把图片/文件附件替换为 `"[Attached {mime}: {filename}]"` 占位文本，
避免在摘要请求中发送大量二进制数据。

**生产影响**：如果未来支持图片输入，摘要时不剥离会浪费大量 token。

### 文件修改时间戳锁

OpenCode 的 `FileTime.withLock()` 在 pruning 操作时加锁，防止并发编辑导致数据不一致。
单线程 Pokkit 不需要，但多 Agent 并行时必须考虑。

**生产影响**：多 Agent 并行 compaction 时可能发生竞态条件。

## 下一步预告

压缩机制就位后，Agent 可以处理长对话了。接下来：
1. **权限系统** — allow / deny / ask 三值逻辑
2. **多 Agent** — Task 工具，子 Agent 并行工作
