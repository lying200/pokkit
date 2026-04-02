# 12 — LSP 集成

## Language Server Protocol

OpenCode 集成了 LSP 客户端，让 AI Agent 能使用 IDE 级别的代码理解能力。

## 提供的能力

| 能力 | 用途 |
|------|------|
| Symbol Lookup | 查找定义、引用、实现 |
| Document Symbols | 提取文件中的函数、类、变量 |
| Hover Info | 类型信息和文档 |
| Call Hierarchy | 函数调用关系（调用者/被调用者） |

## 通信方式

- **stdio** — 标准输入输出
- **HTTP** — HTTP 请求

## 状态管理

LSP 客户端有以下状态：
- `connected` — 正常工作
- `error` — 连接错误
- 需要认证/注册

每个 Instance（项目）有独立的 LSP 连接和符号缓存。

当 LSP 状态变化时，通过 Bus 发布 `lsp.updated` 事件。

## 在 Tool 系统中的体现

`lsp` 工具暴露给 Agent，Agent 可以用它来：
- 跳转到定义
- 查找所有引用
- 获取类型信息

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/lsp/index.ts` | LSP 客户端实现 |
