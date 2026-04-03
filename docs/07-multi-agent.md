# Pokkit 第七步：多 Agent 编排

> **对应 git tag: `v0.7-multi-agent`**

## 为什么做这个

单 Agent 能完成简单任务，但复杂任务需要分工：
- 搜索代码库找到所有相关文件 → 适合一个只读的 explore Agent
- 分析问题制定计划 → 适合一个不能编辑的 planner Agent
- 执行修改 → 主 Agent 自己来

OpenCode 的做法很优雅：**多 Agent 不是一个新系统，就是一个 Task Tool**。
LLM 自己决定什么时候委派，跟调 bash 没区别。

## 从 OpenCode 学到了什么

核心思想：**Agent = 配置（权限 + prompt + model）+ 同一个 while(true) 循环**。

不同的 Agent 不是不同的代码，而是同一个 AgenticLoop 注入不同的配置：
- explore Agent：只给 read/glob/grep，禁止 edit/write
- planner Agent：只读分析，禁止所有编辑
- 主 Agent：全部权限

Task Tool 的工作流程：
```
父 Agent 调用 task(agent="explore", prompt="搜索所有 TODO")
    │
    ▼
创建子 Session（独立的消息历史）
    │
    ▼
用 explore Agent 的配置跑 AgenticLoop（权限受限、独立 prompt）
    │
    ▼
子 Agent 完成后，最终输出作为 Task Tool 的返回值回到父 Agent
```

关键设计：
- 子 Agent 有**独立的消息历史**，不污染父 Agent 的上下文
- 子 Agent **禁止调用 Task**，防止无限嵌套
- 父 Agent 阻塞等待子 Agent 完成

## 实现方案

### 1. AgentConfig — Agent 配置对象

```java
public record AgentConfig(
    String name,           // "coder", "explore", "planner"
    String mode,           // "primary" 或 "subagent"
    String systemPrompt,   // 注入到 AgenticLoop 的 system prompt
    List<Rule> permissionRules,  // 该 Agent 的权限规则
    int maxSteps           // 最大循环步数
) {}
```

### 2. AgentRegistry — 内置 3 个 Agent

| Agent | mode | 权限 | 用途 |
|-------|------|------|------|
| coder | primary | 全部工具 ask，读操作 allow | 默认主 Agent，能读写执行 |
| explore | subagent | 只有 read/glob/grep/bash | 快速搜索代码库 |
| planner | subagent | 只有 read/glob/grep，禁止 bash/edit/write | 只读分析和规划 |

### 3. AgenticLoop 参数化

当前 AgenticLoop 硬编码了 SYSTEM_PROMPT 和依赖外部传入的 PermissionService。
改为接收 AgentConfig，从中获取 system prompt 和构建 PermissionService。

### 4. TaskTool — 普通的 Tool

```java
// LLM 看到的参数
{
  "agent": "explore",           // 子 Agent 名称
  "description": "搜索 TODO",    // 任务描述
  "prompt": "找到所有 TODO 注释"   // 传给子 Agent 的指令
}
```

执行时：
1. 从 AgentRegistry 查找子 Agent 配置
2. 创建新的 `List<Message>` 作为子 Session
3. 用子 Agent 的配置创建新的 AgenticLoop
4. `loop.run(prompt, childHistory)`
5. 提取子 Agent 最后一条 assistant 消息作为返回值

### 5. 权限继承

子 Agent 的权限从 AgentConfig 中获取，不继承父 Agent 的 session 级 always 规则。
所有子 Agent 都禁止 task 工具（防止嵌套）。

## 代码变化

```
新增:
  agent/AgentConfig.java     — Agent 配置 record
  agent/AgentRegistry.java   — Agent 注册表，内置 3 个 Agent
  tool/TaskTool.java         — Task 工具

修改:
  agent/AgenticLoop.java     — 接收 AgentConfig，参数化 system prompt
  cli/Repl.java              — 使用 AgentRegistry，注册 TaskTool
```

## OpenCode 做了但我们简化掉的

以下是 OpenCode 在多 Agent 层面的完整实现中，我们为学习目的简化掉的逻辑。
这些在生产环境中是不可或缺的。

### 子 Session 持久化

OpenCode 的子 Agent 运行在独立的 Session 中，这个 Session 和父 Session 一样会持久化到 SQLite，
包含 parentID 字段建立父子关系。子 Agent 崩溃后可以恢复上下文继续执行。
我们的子 Agent 消息历史是纯内存的 `List<Message>`，跑完就丢。

**生产影响**：长时间运行的子任务（如大范围代码搜索）中途失败后无法恢复，只能重新跑。
子 Agent 的执行过程也无法在 UI 中回放或审计。

### Task 恢复（task_id）

OpenCode 的 Task 工具支持 `task_id` 参数——父 Agent 可以传入之前子 Session 的 ID，
恢复子 Agent 的完整上下文继续工作，而不是每次都从零开始。
这使得"先让 explore 搜索，然后让它基于之前的发现再深入"成为可能。

**生产影响**：没有恢复能力，每次 task 调用都是全新的上下文，子 Agent 无法跨调用积累知识。
对于多轮迭代的复杂子任务（搜索 → 分析 → 深入），每次都要重新搜索。

### 权限继承与约束

OpenCode 的子 Agent 权限是**父 Agent 权限的真子集**——子 Agent 不能拥有父 Agent 没有的权限，
而且通过 `Permission.evaluate("task", agentName, caller.permission)` 检查父 Agent
是否有权委派给特定子 Agent。我们只是简单地从 AgentConfig 读取固定权限，
没有做父子权限的交集计算。

**生产影响**：如果父 Agent 被限制了某个权限（比如用户配置禁止 bash），
子 Agent 仍然可能拥有 bash 权限，突破了安全边界。

### 用户自定义 Agent

OpenCode 支持用户在 `opencode.json` 中配置自定义 Agent——指定 name、prompt、model、
permission 等，甚至可以给不同的 Agent 用不同的 LLM 模型（如主 Agent 用 Claude，
explore 用更便宜的 GPT-4o-mini）。我们的 Agent 定义是硬编码在 AgentRegistry 中的。

**生产影响**：用户无法根据自己的项目需求定制 Agent 行为，也无法利用模型差异化降低成本。

### Batch 并行执行

OpenCode 的 Batch 工具允许同一个 Agent 并行调用多个工具（`Promise.all`，上限 25 个），
如一次性读取 10 个文件。我们所有工具调用都是串行的。

**生产影响**：批量文件操作（读多个文件、搜索多个目录）的延迟线性增长，
复杂任务的执行时间可能数倍于并行方案。

### Agent 模型路由

OpenCode 允许不同 Agent 使用不同的 LLM 模型。主 Agent 可以用最强的模型（Claude Opus），
而 explore 子 Agent 用更快更便宜的模型（GPT-4o-mini 或 Gemini Flash）。
我们所有 Agent 共享同一个 ChatModel。

**生产影响**：无法优化成本——简单的搜索任务和复杂的编码任务用同样价格的模型，浪费预算。

## 不做什么

| 不做 | 原因 |
|------|------|
| 子 Session 持久化 | 子 Agent 的消息历史是临时的，跑完就丢 |
| Task 恢复（task_id） | OpenCode 支持恢复子任务，我们暂不需要 |
| Batch 并行执行 | 同一 Agent 并行调多个工具，后面再说 |
| 用户切换 Agent | OpenCode 的 TUI 支持切换主 Agent，我们先不做 |
