# 09 — Plugin 系统

## 插件架构

OpenCode 支持三种插件来源：

1. **内置插件** — CodexAuth, CopilotAuth, GitlabAuth, PoeAuth
2. **NPM 包** — `@opencode-ai/*` 作用域的包
3. **本地路径** — 文件系统上的本地插件

## Plugin Hook

插件通过 Hook 机制扩展系统行为：

| Hook | 触发时机 | 用途 |
|------|---------|------|
| `experimental.chat.system.transform` | System Prompt 构建时 | 修改系统提示词 |
| `experimental.text.complete` | 文本生成完成后 | 后处理生成文本 |
| `chat.params` | LLM 调用前 | 覆盖 temperature/topP 等 |
| `chat.headers` | HTTP 请求发送前 | 添加自定义 Header |

## 插件配置

```json
{
  "plugin": [
    "@opencode-ai/some-plugin",
    "./local-plugin",
    {
      "name": "@opencode-ai/advanced-plugin",
      "config": { "key": "value" }
    }
  ]
}
```

## 插件加载流程

```
插件发现 → 验证兼容性 → 检查废弃状态 → 解析入口 → 加载模块 → 注册 Hook
```

验证内容包括：
- 与当前 OpenCode 版本的兼容性
- 是否已废弃
- Server 入口点是否存在

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/plugin/index.ts` | 插件加载和管理 |
| `packages/plugin/` | 插件 SDK (@opencode-ai/plugin) |
