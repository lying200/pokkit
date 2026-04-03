# Pokkit 第二步：流式输出 + 更多工具 + 安全阀

> **对应 git tag: `v0.2-streaming`**

## 为什么做这个

v0.1 的 Agent 能跑了，但体验很差：
- 每次提问要等 LLM 完整生成 + 工具执行完才能看到任何输出
- 只有 bash 和 read 两个工具，Agent 不能写文件、不能搜索文件
- 没有防护，Agent 可能陷入死循环反复调同一个工具

## 流式输出的设计

### 核心变化：`chatModel.call()` → `chatModel.stream()`

v0.1 用的是同步调用：
```java
ChatResponse response = chatModel.call(prompt);  // 阻塞直到 LLM 生成完毕
```

v0.2 改为流式：
```java
Flux<ChatResponse> flux = chatModel.stream(prompt);  // 立即返回，逐 token 推送
```

### 流式 + 手动 Tool Calling 怎么配合？

这是最关键的问题。Spring AI 的 `stream()` 返回 `Flux<ChatResponse>`，每个 chunk 包含一小段文本 delta。
当 LLM 决定调用工具时，tool call 信息分散在多个 chunk 里（先是工具名，然后参数 JSON 一段段来）。

我们不需要自己聚合这些碎片。Spring AI 提供了 `MessageAggregator`：

```java
MessageAggregator aggregator = new MessageAggregator();
Flux<ChatResponse> flux = chatModel.stream(prompt);

// aggregate() 做两件事：
// 1. 透传每个 chunk（我们可以打印文本 delta）
// 2. 在流结束时调用 consumer，传入聚合后的完整 ChatResponse（包含完整 tool calls）
AtomicReference<ChatResponse> finalResponse = new AtomicReference<>();
flux = aggregator.aggregate(flux, finalResponse::set);

// 订阅流，逐 chunk 打印文本
flux.doOnNext(chunk -> {
    String text = chunk.getResult().getOutput().getText();
    if (text != null) System.out.print(text);  // 实时打印
}).blockLast();  // 等流结束

// 流结束后，检查聚合结果里有没有 tool calls
ChatResponse response = finalResponse.get();
if (response.hasToolCalls()) {
    // 执行工具，继续循环
}
```

### 循环结构的变化

```
while (true) {
    // 1. 构建 Flux 流
    // 2. 用 MessageAggregator 包装
    // 3. 订阅流，逐 chunk 打印文本到终端
    // 4. blockLast() 等流结束
    // 5. 从聚合结果判断：有 tool call → 执行 → 继续；没有 → break
}
```

本质上循环的骨架没变，只是把"调用 LLM"这一步从同步换成了流式 + 聚合。

## 新增工具

### `write` — 写文件
- 入参：`{ "path": "/some/file.txt", "content": "file content..." }`
- 输出：写入结果（成功/失败）
- 自动创建不存在的父目录

### `glob` — 按模式搜索文件
- 入参：`{ "pattern": "**/*.java", "path": "/some/dir" }`
- 输出：匹配的文件路径列表
- 用 Java 的 `Files.walkFileTree` + `PathMatcher` 实现

这两个加上 v0.1 的 bash 和 read，Agent 就能：读代码 → 搜索文件 → 写代码 → 运行测试。基本的编码循环通了。

## Doom Loop 检测

OpenCode 的做法：记录最近 3 次工具调用，如果工具名和参数完全相同，判定为 doom loop。

我们的实现：
- 在 AgenticLoop 里维护一个滑动窗口（最近 3 次 tool call 的 name + arguments）
- 每次工具调用前检查：如果和前两次完全一样，打印警告并跳出循环
- 简单粗暴，但有效防止 Agent 卡在同一个死循环里

## OpenCode 做了但我们简化掉的

### 工具输出截断策略

OpenCode 对超长工具输出（>2000 行或 >50KB）会写入临时文件，只给 LLM 前后各一段摘要。
我们简单地在 10000 字符处截断（`MAX_OUTPUT_LENGTH`）。

**生产影响**：截断可能丢失关键的错误信息（通常在输出末尾），应保留头尾两段。

### 工具执行上下文（ToolContext）

OpenCode 的每个工具执行时都能访问完整上下文：当前 session、消息历史、agent 信息、
abort 信号。工具可以根据上下文做更智能的决策。我们的工具只拿到 JSON 参数字符串。

**生产影响**：工具无法感知对话上下文，比如无法根据之前的编辑决定是否需要备份。

### 工具结果的结构化元数据

OpenCode 的工具返回不只是文本，还有 `metadata`（如 edit 工具返回 diff、行数变化、LSP 诊断）
和 `title`。TUI 用 metadata 渲染丰富的展示。我们只返回纯文本字符串。

**生产影响**：前端只能展示原始文本，无法做 diff 高亮、错误标注等丰富展示。

### Reasoning/Thinking 部分

OpenCode 支持 LLM 的 extended thinking（如 Claude 的 thinking blocks），
作为 `ReasoningPart` 存储和展示。我们不处理 reasoning 内容。

**生产影响**：无法展示 LLM 的推理过程，对调试和理解 Agent 行为不利。

## 不做什么

| 不做 | 原因 |
|------|------|
| Session 持久化 | 还不需要 |
| 消息压缩 | 对话不长的时候不需要 |
| TUI（终端 UI） | `System.out.print` 的流式输出够用 |
| 权限系统 | 信任所有工具调用 |
| 取消/中断 | 后面再加 Ctrl+C 支持 |
