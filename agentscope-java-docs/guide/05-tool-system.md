# 05 — 工具系统

## 整体架构

```
Toolkit (门面/注册中心)
├── ToolGroupManager    — 工具分组和激活状态
├── ToolRegistry        — 工具存储和查找
├── ToolSchemaProvider  — Schema 生成（仅返回活跃组的工具）
├── ToolExecutor        — 执行基础设施（超时/重试/调度）
├── McpClientManager    — MCP 客户端生命周期
└── MetaToolFactory     — 动态工具管理元工具
```

## 工具注册方式

### 方式 1：@Tool 注解（最常用）

```java
public class WeatherTools {

    @Tool(name = "get_weather", description = "查询城市天气")
    public String getWeather(
        @ToolParam(name = "city", description = "城市名称", required = true) String city,
        @ToolParam(name = "unit", description = "温度单位") String unit
    ) {
        return "北京今天 25°C";
    }
}

// 注册
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherTools());
```

框架自动：
- 扫描 `@Tool` 注解方法
- 从 `@ToolParam` 生成 JSON Schema
- 包装为 `AgentTool` 实现

### 方式 2：AgentTool 接口

```java
public interface AgentTool {
    String getName();
    String getDescription();
    Map<String, Object> getParameters();       // JSON Schema
    Mono<ToolResultBlock> callAsync(ToolCallParam param);
}
```

手动实现，适合需要异步执行或复杂逻辑的工具。

### 方式 3：MCP 工具

通过 MCP 协议从外部服务器获取工具：

```java
McpClientWrapper mcpClient = McpAsyncClientWrapper.builder()
    .transport(new StdioTransport("npx", "-y", "@some/mcp-server"))
    .build();

toolkit.registerMcpClient(mcpClient);
// 自动获取远程工具列表，注册为本地 AgentTool
```

支持三种传输协议：StdIO、SSE、HTTP。

### 方式 4：SubAgent as Tool

把另一个 Agent 注册为工具，供当前 Agent 调用：

```java
toolkit.registration()
    .subAgent(explorerAgent, "explore_code", "探索代码库")
    .register();
```

## JSON Schema 自动生成

框架使用 Victools JSON Schema Generator 从 Java 方法签名自动生成 Schema：

```java
@Tool(name = "search")
public String search(
    @ToolParam(name = "query", description = "搜索关键词", required = true) String query,
    @ToolParam(name = "limit", description = "返回数量") int limit
) { ... }
```

自动生成：

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "搜索关键词"
    },
    "limit": {
      "type": "integer",
      "description": "返回数量"
    }
  },
  "required": ["query"]
}
```

**对比 Pokkit**：Pokkit 需要手动写 `parameterSchema()` 返回 JSON 字符串。AgentScope 从注解自动生成，不容易出错。

## 工具执行管线

```
ToolExecutor.execute(ToolCallParam)
  │
  ├── 1. Schema 校验 (ToolValidator)
  │   └── networknt JSON Schema Validator (Draft 2020-12)
  │   └── 校验失败 → 返回错误 ToolResultBlock（不抛异常）
  │
  ├── 2. 参数合并
  │   └── preset 参数 + 用户输入参数（输入优先）
  │
  ├── 3. 执行 tool.callAsync(param)
  │
  ├── 4. 基础设施层叠加（按顺序）
  │   ├── Scheduling — Schedulers.boundedElastic() 或自定义线程池
  │   ├── Timeout — execution.timeout(Duration)
  │   ├── Retry — 指数退避 + 抖动 + 过滤条件
  │   └── Shutdown Guard — 与 GracefulShutdownManager 竞争
  │
  └── 5. 错误处理
      └── 异常 → ToolResultBlock.error()（不传播）
```

### 并行 vs 顺序执行

```java
// ToolExecutor.executeAll()
Mono<List<ToolResultBlock>> executeAll(
    List<ToolUseBlock> toolCalls,
    boolean parallel,            // true = 并行，false = 顺序
    ExecutionConfig config,
    Agent agent,
    ToolExecutionContext context
);
```

**对比 Pokkit**：Pokkit 只有顺序执行，一次处理一个工具调用。AgentScope 支持并行执行多个工具，显著加速多工具场景。

### 超时和重试配置

```java
ExecutionConfig toolConfig = ExecutionConfig.builder()
    .timeout(Duration.ofMinutes(5))      // 单次执行超时
    .maxAttempts(3)                       // 最大尝试次数
    .initialBackoff(Duration.ofSeconds(2)) // 初始退避
    .maxBackoff(Duration.ofSeconds(30))    // 最大退避
    .backoffMultiplier(2.0)               // 指数因子
    .retryOn(error -> error instanceof TimeoutException)  // 重试条件
    .build();
```

默认值：
- Model: 5 分钟超时，3 次尝试，指数退避
- Tool: 5 分钟超时，1 次尝试（不重试）

**对比 Pokkit**：Pokkit 的 BashTool 有 30 秒硬编码超时，其他工具无超时。没有重试机制。

## 工具分组

工具可以分组管理，Agent 可以动态激活/停用工具组：

```java
// 注册时指定分组
toolkit.registration()
    .group("network")
    .tool(new HttpTool())
    .tool(new DnsTool())
    .register();

toolkit.registration()
    .group("file")
    .tool(new ReadTool())
    .tool(new WriteTool())
    .register();

// 运行时动态管理
toolkit.updateToolGroups(List.of("network"), true);   // 激活
toolkit.updateToolGroups(List.of("file"), false);      // 停用
```

配合 MetaTool，Agent 可以自主决定激活哪些工具组。

## MCP 集成细节

### McpClientWrapper 抽象

```java
public abstract class McpClientWrapper {
    abstract Mono<Void> initialize();
    abstract Mono<List<McpSchema.Tool>> listTools();
    abstract Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> args);
    abstract void close();
}
```

两个实现：
- `McpAsyncClientWrapper` — 异步/并发
- `McpSyncClientWrapper` — 同步阻塞

### McpTool

每个 MCP 远程工具包装为 `McpTool`（实现 AgentTool）：

```java
// McpTool 内部
public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
    Map<String, Object> mergedArgs = new HashMap<>(presetArguments);
    mergedArgs.putAll(param.getInput());  // 输入参数优先
    return clientWrapper.callTool(name, mergedArgs)
        .map(McpContentConverter::convertCallToolResult);
}
```

支持：
- Preset 参数（对 LLM 隐藏，自动合并）
- 工具过滤（include/exclude 列表）
- Schema $ref 解析

## 工具流式输出 (ToolEmitter)

工具可以在执行过程中实时推送中间结果：

```java
@Tool(name = "long_running_task")
public String longTask(
    @ToolParam(name = "input") String input,
    @ToolEmitter ToolEmitterSink emitter      // 注入 emitter
) {
    emitter.emit("Step 1: 开始处理...");
    // ... 处理逻辑 ...
    emitter.emit("Step 2: 50% 完成...");
    // ... 继续 ...
    return "最终结果";
}
```

emitter 的输出通过 `ActingChunkEvent` 推送到 `Flux<Event>` 流中，UI 可以实时展示进度。

**对比 Pokkit**：Pokkit 工具只有最终结果，没有中间进度输出。
