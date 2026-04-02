# 17 — 事件总线

## Bus 架构

OpenCode 使用基于 Effect PubSub 的事件总线进行模块间通信。

## 核心 API

```typescript
// 定义事件
const MyEvent = BusEvent.define("my.event", z.object({
  field: z.string()
}))

// 发布事件
Bus.publish(MyEvent, { field: "value" })

// 流式订阅
Bus.subscribe(MyEvent, (event) => {
  console.log(event.properties.field)
})

// 回调订阅
Bus.subscribeCallback(MyEvent, (event) => {
  // 处理事件
})
```

## 系统事件

| 事件 | 触发时机 |
|------|---------|
| `command.executed` | 命令执行完成 |
| `permission.asked` | 权限请求发起 |
| `permission.replied` | 权限请求回复 |
| `lsp.updated` | LSP 状态变化 |
| `mcp.tools.changed` | MCP 工具可用性变化 |
| `server.instance.disposed` | Instance 生命周期事件 |

## 全局 Bus

除了 Effect 内部的 PubSub，还有一个全局 Bus 用于跨进程通信（如 TUI → Server）。

## 设计要点

- 事件定义使用 Zod schema，支持运行时验证和 OpenAPI 生成
- 强类型：订阅者获得完整的类型推导
- 解耦：模块间不直接依赖，通过事件通信

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/bus/index.ts` | Bus 服务实现 |
| `src/bus/bus-event.ts` | 事件定义和注册 |
