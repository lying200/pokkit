# 10 — 结构化输出

## 问题

LLM 返回的是自由文本。业务系统需要结构化数据（JSON 对象、类型安全的 Java 对象）。简单地在 prompt 里说 "请返回 JSON" 不够可靠——LLM 经常格式错误、字段缺失。

## AgentScope 的方案

### 使用方式

```java
// 定义输出结构
public record WeatherResult(
    String city,
    double temperature,
    String condition
) {}

// 调用 Agent，指定输出类型
Mono<Msg> result = agent.call(userMsg, WeatherResult.class);

// 获取结构化数据
WeatherResult weather = result.block().getStructuredData(WeatherResult.class);
```

### 实现原理

核心思路：**把结构化输出伪装成工具调用**。

```
1. 从 Java 类自动生成 JSON Schema
2. 注册临时工具 "generate_response"（参数就是 Schema）
3. 注册 StructuredOutputHook 监控执行
4. 执行正常 ReAct 循环
5. LLM "调用" generate_response 工具时传入结构化数据
6. Hook 拦截，校验数据是否符合 Schema
7. 校验通过 → 提取数据，返回
8. 校验失败 → gotoReasoning() 重试
9. 循环结束后清理临时工具和 Hook
```

### StructuredOutputCapableAgent

`ReActAgent` 继承 `StructuredOutputCapableAgent`，后者提供：

```java
protected final Mono<Msg> doCall(List<Msg> msgs, Class<?> structuredOutputClass) {
    return Mono.defer(() -> {
        // 1. 生成 JSON Schema
        Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(targetClass);

        // 2. 创建临时工具
        AgentTool tool = createStructuredOutputTool(schema, targetClass);
        toolkit.registerAgentTool(tool);

        // 3. 创建流程控制 Hook
        StructuredOutputHook hook = new StructuredOutputHook(
            reminder, generateOptions, getMemory());
        addHook(hook);

        // 4. 执行正常循环
        return doCall(msgs)
            .flatMap(result -> {
                Msg hookResult = hook.getResultMsg();
                return Mono.just(hookResult != null ? hookResult : result);
            })
            // 5. 清理
            .doFinally(signal -> {
                removeHook(hook);
                toolkit.removeToolIfSame("generate_response", tool);
            });
    });
}
```

### StructuredOutputHook 的 gotoReasoning

当 LLM 的输出不符合 Schema 时：

```java
case PostReasoningEvent e -> {
    // 检查 LLM 是否调用了 generate_response
    // 如果参数不符合 Schema:
    Msg errorMsg = Msg.builder()
        .role(MsgRole.USER)
        .content(TextBlock.builder()
            .text("输出格式错误：" + validationError + "，请重新生成")
            .build())
        .build();
    e.gotoReasoning(List.of(errorMsg));
    // 循环回到推理阶段，不增加 iter 计数
}
```

### RemindMode — 提示策略

```java
public enum RemindMode {
    TOOL_CHOICE,  // 通过 tool_choice 参数强制 LLM 调用指定工具
    PROMPT        // 在 system prompt 中注入格式要求
}
```

## 从 JSON Schema 生成

```java
// 从 Java 类
Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromClass(WeatherResult.class);

// 从 JsonNode（手动定义）
Map<String, Object> schema = JsonSchemaUtils.generateSchemaFromJsonNode(jsonNode);
```

生成的 Schema 示例：

```json
{
  "type": "object",
  "properties": {
    "city": { "type": "string" },
    "temperature": { "type": "number" },
    "condition": { "type": "string" }
  },
  "required": ["city", "temperature", "condition"]
}
```

## 多轮重试的元数据合并

结构化输出可能需要多轮推理。Hook 收集每轮的 token 用量和 thinking 内容：

```java
// StructuredOutputHook 内部
private ChatUsage aggregatedUsage;      // 累积 token 用量
private List<ThinkingBlock> thinkings;  // 累积思考过程
```

最终返回的 Msg 包含所有轮次的合并元数据。

## 对比 Pokkit

| 维度 | AgentScope | Pokkit |
|------|-----------|--------|
| 结构化输出 | 原生支持，类型安全 | 不支持 |
| 实现方式 | 临时工具 + Hook | - |
| 自动重试 | gotoReasoning() 无限重试 | - |
| Schema 来源 | 从 Java 类自动生成 | - |
| 验证 | networknt Validator | - |

这是 Pokkit 缺失的重要功能。如果要实现，核心思路：
1. 先实现 Hook 系统
2. 然后结构化输出自然变成一个 Hook + 临时工具的组合
