# 02 — 架构全景

## 分层架构

OpenCode 采用多层架构，从上到下分为：

```
┌─────────────────────────────────────────────────────────┐
│                    客户端层 (Client)                      │
│   TUI (SolidJS+opentui) │ Web App │ Desktop (Tauri)     │
├─────────────────────────────────────────────────────────┤
│                    服务层 (Server)                        │
│   Hono HTTP │ WebSocket │ OpenAPI │ Auth                 │
├─────────────────────────────────────────────────────────┤
│                    Agent 层                               │
│   Build Agent │ Plan Agent │ General Agent │ Explore     │
│   Permission │ Agent Routing │ Model Selection           │
├─────────────────────────────────────────────────────────┤
│                    工具层 (Tool)                          │
│   40+ 内置工具 │ MCP 工具 │ 自定义工具                    │
│   权限检查 │ 输出截断 │ 工具注册表                        │
├─────────────────────────────────────────────────────────┤
│                    LLM 集成层                             │
│   Vercel AI SDK │ 30+ Provider │ Stream 处理             │
│   Plugin Hooks │ System Prompt │ Token 管理              │
├─────────────────────────────────────────────────────────┤
│                    存储层 (Storage)                       │
│   SQLite (WAL) │ Drizzle ORM │ Migration                │
├─────────────────────────────────────────────────────────┤
│                    基础设施层                              │
│   Effect System │ Bus (PubSub) │ Config │ Instance      │
└─────────────────────────────────────────────────────────┘
```

## 完整数据流：从用户输入到响应输出

```
用户输入 (CLI/TUI/Web)
    │
    ▼
RunCommand → createOpencodeClient()     # SDK 客户端
    │
    ▼
Client.message()                         # HTTP POST 到服务端
    │
    ▼
Server.routes.session[id].message        # Hono 路由处理
    │
    ├─► Session.create() 或 Session.getLatest()
    │
    ├─► 解析用户消息 → MessageV2.User
    │
    ├─► Session.updateMessage()          # 持久化
    │
    ├─► Agent.get()                      # 加载 Agent 配置
    │
    ├─► Permission.ask()                 # 权限检查 (如需要)
    │
    ├─► Tool.registry.all()              # 构建可用工具列表
    │
    ▼
LLM.stream()                            # 开始流式请求
    │
    ├─► Provider.getLanguage(model)      # 获取提供者
    ├─► Plugin.trigger("chat.params")    # 插件 Hook
    ├─► ai-sdk streamText()              # Vercel AI SDK 调用
    │
    ▼
SessionProcessor.handleEvent() 循环      # 处理流事件
    │
    ├─ text-start/delta/end  → TextPart       # 文本生成
    ├─ reasoning-start/end   → ReasoningPart  # 推理过程
    ├─ tool-call             → ToolPart       # 工具调用
    │   └─► Tool.execute()   → ToolResult     # 执行工具
    ├─ finish-step           → StepPart       # 步骤完成
    └─ Snapshot.track()      → PatchPart      # 文件变更跟踪
    │
    ▼
MessageV2.Assistant 更新                  # 累计 cost/tokens
    │
    ├─► Bus.publish("command.executed")  # 发布事件
    ├─► SessionSummary.summarize()       # 异步摘要
    │
    ▼
返回消息到客户端 → 格式化展示
```

## 核心抽象与接口

### 1. Effect 服务模式

几乎所有核心模块都采用 Effect-TS 的服务模式：

```typescript
// 标准模式：每个模块定义 Interface、Service、layer
export namespace Module {
  export interface Interface {
    method1(...): Effect<Result>
  }

  // Service 是 Effect 的 Tag，用于依赖注入
  export class Service extends ServiceMap.Service<Service, Interface>() {}

  // layer 是服务的工厂，声明依赖并构造实现
  export const layer = Layer.effect(
    Service,
    Effect.gen(function*() {
      const dep1 = yield* Dependency1.Service
      return Service.of({ method1: ... })
    })
  )
}
```

### 2. 消息结构 (MessageV2)

消息由多个 Part 组成，是系统的核心数据结构：

```typescript
// 用户消息
MessageV2.User {
  role: "user"
  parts: (TextPart | FilePart)[]
}

// 助手消息
MessageV2.Assistant {
  role: "assistant"
  parts: (TextPart | ReasoningPart | ToolPart | PatchPart | StepPart)[]
}
```

### 3. 工具接口

```typescript
Tool.Info {
  id: string
  init(ctx?) → {
    description: string
    parameters: ZodSchema          // 输入验证
    execute(args, ctx) → {         // 执行
      title, metadata, output, attachments
    }
  }
}
```

### 4. Agent 配置

```typescript
Agent.Info {
  name: string                     // build, plan, general, explore
  mode: "primary" | "subagent"     // 主/子 Agent
  permission: Ruleset              // 权限规则集
  model?: { providerID, modelID }  // 指定模型
  prompt?: string                  // 自定义 System Prompt
  temperature?: number
  steps?: number                   // 最大推理步数
}
```

### 5. 事件总线

```typescript
// 定义事件
const MyEvent = BusEvent.define("my.event", z.object({
  field: z.string()
}))

// 发布
Bus.publish(MyEvent, { field: "value" })

// 订阅 (流式)
Bus.subscribe(MyEvent, (event) => { ... })
```

## 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 效果系统 | Effect-TS | 函数式组合、依赖注入、错误处理 |
| 数据库 | SQLite + Drizzle | 本地优先、零配置、类型安全 |
| UI 框架 | SolidJS | 细粒度响应性，TUI 场景高性能 |
| AI SDK | Vercel AI SDK | 统一的多 Provider 抽象 |
| 运行时 | Bun | 速度快、原生 SQLite/TS 支持 |
| 消息协议 | MCP | 业界标准的工具/资源协议 |
| 序列化 | Zod | 运行时验证 + TypeScript 类型推导 |

## 模块依赖关系

```
Config ──► 被所有模块依赖
  │
  ▼
Bus ──► 事件通信基础
  │
  ▼
Storage ──► Session, Permission, Project
  │
  ▼
Provider ──► LLM (Session Processor)
  │
  ▼
Agent ──► 依赖 Permission, Tool, Provider
  │
  ▼
Tool ──► 被 Agent 调度, 依赖 Permission
  │
  ▼
Session ──► 依赖 Storage, Agent, Tool, LLM
  │
  ▼
Server ──► 暴露 Session, Agent, Config 等为 API
  │
  ▼
CLI/TUI ──► 消费 Server API 或直接调用 SDK
```
