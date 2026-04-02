# 08 — Provider 系统

## 提供者抽象

OpenCode 通过 Vercel AI SDK 实现了统一的 LLM 提供者抽象，支持 30+ 提供者。

## 内置提供者

| 提供者 | SDK 包 | 说明 |
|--------|--------|------|
| Anthropic | @ai-sdk/anthropic | Claude 系列 |
| OpenAI | @ai-sdk/openai | GPT 系列 |
| Google | @ai-sdk/google | Gemini 系列 |
| Google Vertex | @ai-sdk/google-vertex | Vertex AI |
| Azure | @ai-sdk/azure | Azure OpenAI |
| Mistral | @ai-sdk/mistral | Mistral 系列 |
| Groq | @ai-sdk/groq | 快速推理 |
| DeepInfra | @ai-sdk/deepinfra | 多模型推理 |
| Cerebras | @ai-sdk/cerebras | 快速推理 |
| Cohere | @ai-sdk/cohere | Cohere 系列 |
| OpenRouter | 自定义 | 多模型路由 |
| GitHub Copilot | 自定义 | Copilot 模型 |
| GitLab | 自定义 | GitLab AI |
| Perplexity | 自定义 | 搜索增强 |
| xAI | 自定义 | Grok 系列 |
| 自定义 | 动态加载 | 用户自定义 |

## 模型能力

```typescript
Provider.Model {
  id: string
  name: string
  provider: string
  limit: {
    context: number        // 上下文窗口大小
    output: number         // 最大输出 token
  }
  cost?: {
    input: number          // 输入 token 单价
    output: number         // 输出 token 单价
  }
  options?: {              // 模型特定选项
    reasoning?: boolean    // 支持 Extended Thinking
    json?: boolean         // 支持结构化输出
  }
  variants?: string[]      // 模型变体
}
```

## 自定义提供者

可以通过配置加载自定义提供者模块：

```json
{
  "provider": {
    "my-provider": {
      "module": "./my-provider.ts",
      "apiKey": "$MY_API_KEY"
    }
  }
}
```

变量替换：配置中的 `$VAR_NAME` 会被替换为环境变量或 `variable` 配置中的值。

## LLM 调用流程

```
Agent 选择模型
    │
    ▼
Provider.getLanguage(model)  → 获取 AI SDK 实例
    │
    ▼
Plugin.trigger("chat.params")  → 插件修改参数
Plugin.trigger("chat.headers") → 插件添加 Header
Plugin.trigger("experimental.chat.system.transform") → 修改 System Prompt
    │
    ▼
ai-sdk streamText({
  model,
  system,         // System Prompt
  messages,       // 对话历史
  tools,          // 可用工具
  temperature,    // 温度
  maxTokens,      // 最大 token
  ...options
})
    │
    ▼
流式响应 → SessionProcessor 处理
```

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/provider/provider.ts` | 提供者定义和加载 |
| `src/provider/` | 提供者模块全部 |
