# 03 — Effect 系统与依赖注入

## 为什么用 Effect-TS

OpenCode 重度依赖 [Effect-TS](https://effect.website/) 作为其基础设施层。Effect 提供了：

- **依赖注入** — 声明式的服务依赖，无需手动传递
- **错误处理** — 类型安全的错误通道
- **资源管理** — 自动清理（Scope/Finalizer）
- **并发控制** — 结构化并发
- **可组合性** — 函数式管道

如果你没有 Effect-TS 经验，建议先花 1-2 小时阅读官方文档的 Getting Started 部分。这是理解 OpenCode 代码的前提。

## 核心模式

### Service 定义

项目中几乎每个模块都遵循这个模式：

```typescript
// 文件: src/some-module/index.ts
import { ServiceMap } from "../effect/service-map"

export namespace SomeModule {
  // 1. 定义接口
  export interface Interface {
    doSomething(input: string): Effect<Result, SomeError>
    getState(): Effect<State>
  }

  // 2. 创建 Service Tag (用于依赖注入的标识符)
  export class Service extends ServiceMap.Service<Service, Interface>() {}

  // 3. 构建 Layer (工厂函数，声明依赖)
  export const layer = Layer.effect(
    Service,
    Effect.gen(function*() {
      // 获取依赖
      const config = yield* Config.Service
      const bus = yield* Bus.Service

      // 返回实现
      return Service.of({
        doSomething: (input) => Effect.gen(function*() {
          // 使用依赖实现逻辑
        }),
        getState: () => Effect.succeed(...)
      })
    })
  )
}
```

**关键文件**: `packages/opencode/src/effect/service-map.ts`

### 使用 Service

```typescript
// 在 Effect 上下文中获取 Service
const result = yield* SomeModule.Service
const data = yield* result.doSomething("hello")

// 或者更简洁
const data = yield* SomeModule.Service.pipe(
  Effect.flatMap(s => s.doSomething("hello"))
)
```

## Instance 与 InstanceState

OpenCode 支持同时管理多个项目目录，每个目录是一个 Instance。

**关键文件**: `packages/opencode/src/project/instance.ts`

### Instance 上下文

```typescript
InstanceContext {
  directory: string     // 项目根目录
  worktree: string      // Git worktree 或项目根
  project: Project.Info // 项目元数据
}
```

### InstanceState — 按目录隔离的状态

```typescript
// 创建按实例隔离的状态
const myState = InstanceState.make(
  // 初始化函数
  () => Effect.succeed({ count: 0 }),
  // 清理函数 (可选)
  (state) => Effect.succeed(void 0)
)

// 使用：在当前 Instance 上下文中自动获取对应状态
const state = yield* myState
```

### Instance 生命周期

```typescript
// 创建/复用实例
Instance.provide({
  directory: "/path/to/project",
  fn: () => Effect.gen(function*() {
    // 在实例上下文中执行
  })
})

// 绑定上下文（用于异步回调）
const boundFn = Instance.bind(myAsyncFunction)

// 释放/重载
Instance.dispose()
Instance.reload()
```

## Runtime 管理

**关键文件**: `packages/opencode/src/effect/runtime.ts`

```typescript
// makeRuntime 创建带缓存的运行时
const runtime = makeRuntime(
  Layer.mergeAll(
    Config.layer,
    Bus.layer,
    Storage.layer,
    // ... 其他 Service Layer
  )
)

// 使用运行时执行 Effect
const result = await runtime.runPromise(myEffect)
```

## 常见 Effect 用法

### Generator 语法

```typescript
// 最常见的写法：generator 函数
Effect.gen(function*() {
  const config = yield* Config.Service
  const data = yield* fetchData()
  return process(data)
})
```

### 错误处理

```typescript
// 项目倾向于避免 try/catch，而是用 Effect 的错误通道
Effect.gen(function*() {
  const result = yield* someOperation.pipe(
    Effect.catchTag("NotFound", () => Effect.succeed(defaultValue))
  )
})
```

### 资源管理 (Scope)

```typescript
// Effect.acquireRelease 确保资源清理
const managed = Effect.acquireRelease(
  openConnection(),           // 获取
  (conn) => closeConnection(conn)  // 释放
)
```

## 学习建议

1. **先理解 `ServiceMap`** — 这是项目自定义的 Effect 辅助工具
2. **读 `effect/runtime.ts`** — 理解运行时如何构建
3. **读 `project/instance.ts`** — 理解多实例管理
4. **选一个简单 Service (如 `bus/`)** — 看完整的 Interface → Service → layer 流程
5. **不要试图一次理解所有 Effect API** — 用到什么查什么
