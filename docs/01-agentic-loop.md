# Pokkit 第一步：最小 Agentic Loop

> **对应 git tag: `v0.1-agentic-loop`**

## 我们要做什么

一句话：**让 LLM 能自己决定调用工具，拿到结果后继续思考，直到它认为任务完成。**

这就是所有 AI Agent 的核心——一个 while(true) 循环。OpenCode 里叫 `runLoop()`，我们叫它 `AgenticLoop`。

## 从 OpenCode 学到了什么

读完 OpenCode 的源码（`/home/echoyn/code/opencode`），它的 Agent 本质上就是三样东西：
- **配置**（用哪个模型、什么权限、system prompt 是啥）
- **消息历史**（和 LLM 的对话记录）
- **一个 while(true)**（不断调 LLM → 执行工具 → 把结果喂回去）

核心代码在 `packages/opencode/src/session/prompt.ts` 的 `runLoop()` 和 `processor.ts` 里。
它做得很重，有 Effect-TS 依赖注入、SQLite 持久化、多 Agent 编排、Session 压缩、权限系统……
我们第一步全都不要，只要那个循环能转起来。

## 核心流程

```
用户输入一段话
    │
    ▼
AgenticLoop.run(userMessage)
    │
    ▼
while (true) {
    // 1. 把消息历史发给 LLM（通过 Spring AI 的 ChatModel）
    response = chatModel.call(messages)

    // 2. 看 LLM 的回复里有没有 tool call
    if (没有 tool call) {
        // LLM 觉得活干完了，返回最终文本
        break
    }

    // 3. 有 tool call → 找到对应的 Tool → 执行 → 拿到结果
    for (toolCall : response.toolCalls) {
        tool = toolRegistry.get(toolCall.name)
        result = tool.execute(toolCall.arguments)
        // 4. 把工具结果包装成 JSON 追加到消息历史
        messages.add(toolResult(toolCall.id, result))
    }

    // 5. 回到循环顶部，带着工具结果再次调用 LLM
}
```

就这么多。没有花哨的东西。

## 关键设计决策

### 1. Spring AI 只管调模型，循环我们自己写

Spring AI 有内置的 tool calling 自动模式——注册 `@Tool` 方法，它帮你处理工具调用循环。
**但我们不用。** 原因：

- 我们要自己控制循环的每一步（加日志、加限制、加安全检查）
- 后面要做多 Agent、权限、消息压缩，这些都需要在循环里插入逻辑
- 用自动模式等于把 Agent 的灵魂交出去了

**具体怎么做到的**：`DefaultToolCallingChatOptions` 的 `internalToolExecutionEnabled(false)` 开关。
关掉后，LLM 返回 tool call 时 Spring AI 不会自动执行，而是把 `ChatResponse.hasToolCalls()` 设为 true，
我们自己检查、自己执行、自己把结果喂回去。

但 Spring AI 仍然需要知道有哪些工具可用（它要把工具定义发给 LLM），所以我们把自己的 `Tool` 接口
包装成 Spring AI 的 `ToolCallback`，通过 `toolCallbacks()` 传进去。包装很薄——只转 name/description/schema。

### 2. Tool 接口：四个方法够了

```java
public interface Tool {
    String name();              // LLM 通过这个名字调用
    String description();       // 给 LLM 看，决定什么时候用
    String parameterSchema();   // JSON Schema，告诉 LLM 入参长什么样
    String execute(String argumentsJson);  // 执行，返回文本结果
}
```

为什么全是 String？因为在循环层面不需要关心具体类型，参数解析是各 Tool 自己的事。

### 3. 消息历史就是一个 List

`List<Message>` 活在内存里，程序退出就没了。Spring AI 的 `UserMessage`、`AssistantMessage`、
`ToolResponseMessage` 直接用，不自己造。

### 4. 安全阀：maxSteps

循环最多跑 N 轮（默认 20），超了就强制停。简单粗暴但有效。

### 5. 多 Provider 支持：`spring.ai.model.chat` 选择器

同时引入了 OpenAI 和 Google GenAI 两个 starter，通过 Spring AI 2.0 的 `spring.ai.model.chat` 属性
选择激活哪个 provider，未选中的自动不加载。比手动 `autoconfigure.exclude` 优雅得多。

配合 Spring Profile（`application-openai.yml` / `application-google.yml`）分离各 provider 的连接配置，
环境变量 `POKKIT_PROVIDER=openai|google` 一键切换。

## 代码结构

```
src/main/java/com/pokkit/
├── PokkitApplication.java       # Spring Boot 启动
├── agent/
│   └── AgenticLoop.java         # 核心循环（整个项目最重要的文件）
├── tool/
│   ├── Tool.java                # 工具接口（4 个方法）
│   ├── ToolRegistry.java        # 工具注册表（name → Tool 的 map）
│   ├── BashTool.java            # bash 工具（执行 shell 命令，30s 超时）
│   └── ReadTool.java            # read 工具（读文件，最多 2000 行）
└── cli/
    └── Repl.java                # 命令行 REPL（Scanner 循环）

src/main/resources/
├── application.yml              # 公共配置 + spring.ai.model.* 选择器
├── application-openai.yml       # OpenAI 连接配置
└── application-google.yml       # Google GenAI 连接配置
```

## 踩过的坑

### Spring Boot 4 + Jackson 3
包名从 `com.fasterxml.jackson` 变成了 `tools.jackson`。
用 `JsonMapper.shared().readValue(json, Map.class)` 解析，别引旧包。

### Gemini 要求 tool response 是 JSON
OpenAI 接受纯文本的 tool response，但 Gemini SDK 会尝试把 tool response 解析为 JSON。
如果返回纯文本（如 `ls` 输出），直接报 `StreamReadException`。
解决：把工具输出包装成 `{"result":"..."}` 再传回去，对 OpenAI 也兼容。

### Gemini OpenAI 兼容 API 的 thought_signature 问题
最初尝试用 Gemini 的 OpenAI 兼容端点（`spring-ai-starter-model-openai` + Gemini base-url），
tool call 回传时报 `thought_signature missing`。这是 Gemini 兼容层的已知限制。
解决：直接用原生的 `spring-ai-starter-model-google-genai`。

### 多 Provider 自动配置冲突
同时引入 OpenAI 和 Google GenAI starter 后，没配 key 的那个会在启动时报错。
最初用 `spring.autoconfigure.exclude` 手动排除，要列一堆类名。
更优方案：Spring AI 2.0 的 `spring.ai.model.chat=<provider>` 直接选择，未选中的不加载。

## OpenCode 做了但我们简化掉的

以下是 OpenCode 在 Agentic Loop 层面的完整实现中，我们为学习目的简化掉的逻辑。

### Effect-TS 依赖注入

OpenCode 用 Effect-TS 做依赖注入和错误处理，所有服务都是 `Layer`，通过 `Effect.gen` 组合。
这使得测试和模块替换很方便，但学习成本高。我们直接用构造函数传参。

**生产影响**：没有 DI 容器会让测试和模块替换变困难，Spring 的 DI 可以部分弥补。

### 消息级实时持久化

OpenCode 在 `SessionProcessor` 中，每收到一个流式 chunk（text delta、tool call、tool result）
都立即写入 SQLite。进程崩溃最多丢失一个 chunk。我们在整轮对话结束后才批量保存。

**生产影响**：长时间运行的 tool call（如 bash 命令超时）中途崩溃会丢失该轮所有上下文。

### 取消/中断机制

OpenCode 支持 Ctrl+C 中断当前 LLM 调用或工具执行，通过 `AbortController` 传播取消信号。
我们没有中断机制，Ctrl+C 直接杀进程。

**生产影响**：用户无法优雅地中断一个跑偏的 Agent，只能硬杀。

### 流式 Token 追踪

OpenCode 在 `step-finish` 事件中拿到 provider 返回的真实 token 使用量（input/output/cache），
用于精确的溢出检测和用量统计。我们没有追踪 token。

**生产影响**：无法精确判断何时需要压缩，也无法做用量监控和成本控制。

### Bus 事件系统

OpenCode 的 `Bus` 是一个 pub/sub 事件总线，AgenticLoop 中的每一步（消息更新、工具调用、
权限请求、完成通知）都通过 Bus 发布事件，TUI 和其他消费者监听。我们用 `System.out.println` 直接打印。

**生产影响**：没有事件系统，TUI/Web UI 等多前端无法接入。

## 不做什么（以及为什么）

| 不做 | 原因 |
|------|------|
| 多 Agent | 先让一个 Agent 跑通 |
| 权限系统 | 第一版信任所有工具调用 |
| Session 持久化 | 内存够用，重启丢了就丢了 |
| 消息压缩 | 对话不长的时候不需要 |
| 流式输出 | 先跑通再优化体验 |
| System Prompt 定制 | 先硬编码一个够用的 |
| MCP 协议 | 后面再说 |

## 下一步预告

循环跑通之后，大概率会先加：
1. **流式输出** — 不然等 LLM 想完才能看到输出，体验太差
2. **System Prompt** — 让 Agent 知道自己是谁、能干嘛
3. **更多工具** — write、grep、glob，让 Agent 真的能改代码
