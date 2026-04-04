# 04 — 消息模型

## Msg 类

`Msg` 是 AgentScope 中所有消息传递的核心类型，不可变设计：

```java
public class Msg implements State {
    private final String id;                      // UUID，自动生成
    private final String name;                    // 发送者名称（可选）
    private final MsgRole role;                   // USER / ASSISTANT / SYSTEM / TOOL
    private final List<ContentBlock> content;     // 不可变内容列表
    private final Map<String, Object> metadata;   // 元数据（可变 HashMap）
    private final String timestamp;               // "yyyy-MM-dd HH:mm:ss.SSS"
}
```

### 构建消息

```java
Msg userMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(TextBlock.builder().text("帮我查一下天气").build())
    .build();

Msg assistantMsg = Msg.builder()
    .name("assistant")
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        TextBlock.builder().text("正在查询...").build(),
        ToolUseBlock.builder()
            .id("tool-1")
            .name("get_weather")
            .input(Map.of("city", "北京"))
            .build()
    ))
    .build();
```

### 内容访问

```java
// 获取所有文本
String text = msg.getTextContent();  // 拼接所有 TextBlock

// 获取特定类型的 ContentBlock
List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
ToolUseBlock first = msg.getFirstContentBlock(ToolUseBlock.class);
boolean hasTools = msg.hasContentBlocks(ToolUseBlock.class);

// 结构化数据
boolean hasData = msg.hasStructuredData();
MyClass data = msg.getStructuredData(MyClass.class);

// Token 用量
ChatUsage usage = msg.getChatUsage();

// 完成原因
GenerateReason reason = msg.getGenerateReason();
```

### 不可变更新

```java
Msg updated = msg.withGenerateReason(GenerateReason.MODEL_STOP);
// 返回新对象，原对象不变
```

## ContentBlock 密封类层次

使用 Java sealed class 实现类型安全的多态内容：

```java
public sealed class ContentBlock implements State
    permits TextBlock, ImageBlock, AudioBlock, VideoBlock,
            ThinkingBlock, ToolUseBlock, ToolResultBlock
```

Jackson 通过 `@JsonTypeInfo` 的 `"type"` 字段做多态反序列化。

### TextBlock — 文本内容

```java
TextBlock.builder().text("Hello").build()
// JSON: {"type": "text", "text": "Hello"}
```

### ThinkingBlock — 扩展思考

LLM 的内部推理过程（如 Claude 的 extended thinking）：

```java
ThinkingBlock.builder().text("让我分析一下...").build()
// JSON: {"type": "thinking", "text": "让我分析一下..."}
```

**重要**：Formatter 在发送给 LLM 时会自动过滤 ThinkingBlock，不会回传给模型。

### ImageBlock / AudioBlock / VideoBlock — 多模态

```java
ImageBlock.builder().url("https://...").build()       // URL 引用
ImageBlock.builder().base64("data...").build()         // Base64 内联

AudioBlock.builder().url("https://...").build()
VideoBlock.builder().url("https://...").build()
```

### ToolUseBlock — 工具调用请求

LLM 请求调用某个工具：

```java
ToolUseBlock.builder()
    .id("call_abc123")          // 调用 ID（用于匹配结果）
    .name("get_weather")        // 工具名
    .input(Map.of(              // 参数
        "city", "北京",
        "unit", "celsius"
    ))
    .build()
```

### ToolResultBlock — 工具执行结果

```java
ToolResultBlock.builder()
    .toolUseId("call_abc123")    // 匹配 ToolUseBlock 的 id
    .output(List.of(
        TextBlock.builder().text("北京今天 25°C，晴").build()
    ))
    .build()
```

支持 suspended 状态（工具挂起，等待外部输入）。

## MsgRole 枚举

```java
public enum MsgRole {
    USER,       // 用户输入
    ASSISTANT,  // Agent 输出（推理/工具调用）
    SYSTEM,     // 系统指令/上下文
    TOOL        // 工具执行结果
}
```

## GenerateReason 枚举

描述 Agent 为什么停止生成：

```java
public enum GenerateReason {
    MODEL_STOP,                  // 正常完成
    TOOL_CALLS,                  // 内部工具调用（框架继续循环）
    STRUCTURED_OUTPUT,           // 结构化输出完成
    TOOL_SUSPENDED,              // 工具挂起，等待外部结果
    REASONING_STOP_REQUESTED,    // HITL：推理阶段被人工停止
    ACTING_STOP_REQUESTED,       // HITL：行动阶段被人工停止
    INTERRUPTED,                 // Agent 被中断
    MAX_ITERATIONS               // 达到最大迭代次数
}
```

**对比 Pokkit**：Pokkit 的消息基于 Spring AI 的 `UserMessage` / `AssistantMessage` / `ToolResponseMessage`，内容只是 String，没有 ContentBlock 多态。AgentScope 的 `Msg` 统一了所有消息类型，内容是结构化的 ContentBlock 列表，支持多模态、工具调用等复杂场景。

## 消息流转示例

一个完整的 ReAct 循环中的消息流：

```
1. 用户输入
   Msg(role=USER, content=[TextBlock("帮我查北京天气")])

2. LLM 推理（决定调用工具）
   Msg(role=ASSISTANT, content=[
       TextBlock("我来帮你查一下北京的天气"),
       ToolUseBlock(id="c1", name="get_weather", input={city:"北京"})
   ])

3. 工具执行结果
   Msg(role=TOOL, content=[
       ToolResultBlock(toolUseId="c1", output=[TextBlock("25°C，晴")])
   ])

4. LLM 最终回复
   Msg(role=ASSISTANT, content=[
       TextBlock("北京今天天气晴朗，气温 25°C，适合出行。")
   ])
   metadata: {generate_reason: MODEL_STOP}
```
