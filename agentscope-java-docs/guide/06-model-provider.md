# 06 — Model Provider 系统

## Model 接口

极简的 LLM 抽象，只有一个核心方法：

```java
public interface Model {
    Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
    String getModelName();
}
```

所有 LLM 调用都是**流式**的（返回 `Flux<ChatResponse>`），非流式场景通过 `last()` 获取最终结果。

## ChatModelBase — 模板方法

```java
public abstract class ChatModelBase implements Model {
    @Override
    public final Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 包装追踪
        return TracerRegistry.get().callModel(this, messages, tools, options,
            () -> doStream(messages, tools, options));
    }

    protected abstract Flux<ChatResponse> doStream(
        List<Msg> messages, List<ToolSchema> tools, GenerateOptions options);
}
```

子类只需实现 `doStream()`，追踪自动处理。

## 支持的 Provider

| Provider | 实现类 | Formatter | SDK |
|----------|-------|-----------|-----|
| DashScope (通义) | `DashScopeChatModel` | `DashScopeChatFormatter` | dashscope-sdk-java |
| OpenAI | `OpenAIChatModel` | `OpenAIChatFormatter` | OkHttp (自建) |
| Gemini | `GeminiChatModel` | `GeminiChatFormatter` | google-genai |
| Anthropic | `AnthropicChatModel` | `AnthropicChatFormatter` | anthropic-java |
| Ollama | `OllamaChatModel` | `OllamaChatFormatter` | OkHttp (自建) |
| DeepSeek | (复用 OpenAI) | `DeepSeekFormatter` | OkHttp |
| GLM (智谱) | (复用 OpenAI) | `GLMFormatter` | OkHttp |

## Formatter 体系 — 核心设计

这是 AgentScope 与 Pokkit 最大的架构差异之一。Spring AI 把格式转换藏在内部，AgentScope 显式管理：

### Formatter 接口

```java
public interface Formatter<TReq, TResp, TParams> {
    // AgentScope Msg → Provider 请求格式
    List<TReq> format(List<Msg> msgs);

    // Provider 响应 → AgentScope ChatResponse
    ChatResponse parseResponse(TResp response, Instant startTime);

    // 应用采样参数
    void applyOptions(TParams paramsBuilder, GenerateOptions options, GenerateOptions defaultOptions);

    // 应用工具定义
    void applyTools(TParams paramsBuilder, List<ToolSchema> tools);

    // 应用工具选择策略
    void applyToolChoice(TParams paramsBuilder, ToolChoice toolChoice);
}
```

泛型参数：
- `TReq` — Provider 的请求消息类型
- `TResp` — Provider 的响应类型
- `TParams` — Provider 的请求构建器类型

### 为什么需要 Formatter？

各 Provider API 差异巨大：

```
OpenAI:    messages: [{role: "user", content: "hello"}]
Anthropic: messages: [{role: "user", content: [{type: "text", text: "hello"}]}]
Gemini:    contents: [{role: "user", parts: [{text: "hello"}]}]
DashScope: input.messages: [{role: "user", content: "hello"}]
```

工具调用格式更不同：
- OpenAI 用 `tool_calls` 数组 + `function` 对象
- Anthropic 用 `content` 中的 `tool_use` block
- Gemini 用 `functionCall` part

Formatter 将这些差异封装在各自的实现中，核心框架只操作统一的 `Msg` 和 `ChatResponse`。

### AbstractBaseFormatter 共享逻辑

所有 Formatter 共享的基类，处理：

- TextBlock 提取（过滤 ThinkingBlock）
- 多模态内容检测（image/audio/video）
- 工具结果到文本的转换
- Base64 数据临时文件处理
- 历史合并旁路检测

### 示例：OpenAIChatFormatter

```java
public class OpenAIChatFormatter extends OpenAIBaseFormatter {

    @Override
    public void applyOptions(OpenAIRequest params, GenerateOptions options, GenerateOptions defaults) {
        // temperature, top_p, frequency_penalty, presence_penalty
        // reasoning_effort (OpenAI o1 系列)
        // max_tokens vs max_completion_tokens
        // seed, stop, response_format
    }

    @Override
    public void applyTools(OpenAIRequest params, List<ToolSchema> tools) {
        // 转换为 OpenAI 的 tools 格式
        // 支持 strict mode（JSON Schema 严格验证）
    }

    @Override
    public void applyToolChoice(OpenAIRequest params, ToolChoice choice) {
        // auto / none / required / specific tool name
    }
}
```

### DeepSeek / GLM 的复用

DeepSeek 和 GLM 的 API 兼容 OpenAI 格式，但有细微差异：

```java
public class DeepSeekFormatter extends OpenAIChatFormatter {
    @Override
    protected boolean supportsStrict() {
        return false;  // DeepSeek 不支持 strict mode
    }
}
```

## GenerateOptions — 采样参数

```java
GenerateOptions options = GenerateOptions.builder()
    .temperature(0.7)
    .topP(0.9)
    .maxTokens(4096)
    .frequencyPenalty(0.0)
    .presencePenalty(0.0)
    .seed(42)
    .stop(List.of("\n\n"))
    .reasoningEffort("medium")    // OpenAI o1 系列
    .build();
```

## ChatResponse

LLM 的标准化响应：

```java
public class ChatResponse {
    private final List<Msg> messages;      // 响应消息
    private final ChatUsage usage;         // Token 用量
    private final String finishReason;     // stop / tool_calls / length
}

public class ChatUsage {
    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;
}
```

**对比 Pokkit**：

| 维度 | AgentScope | Pokkit |
|------|-----------|--------|
| LLM 调用 | 自研 Model + Formatter | Spring AI ChatModel |
| 格式转换 | 显式 Formatter 接口 | Spring AI 内部处理 |
| Token 统计 | ChatUsage 精确记录 | text.length / 4 估算 |
| 流式响应 | Flux<ChatResponse> 原生支持 | MessageAggregator 收集 |
| Provider 数量 | 7 个 | 2 个 (OpenAI, Gemini) |
| 控制粒度 | 完全自主，逐字段控制 | 受限于 Spring AI 抽象 |

AgentScope 选择自建而非依赖 Spring AI，代价是更多代码，收益是对每个 Provider 的精确控制——这在生产环境中很重要，因为各 Provider 的 API 行为差异经常导致 bug。
