# 11 — 多 Agent 编排

## Pipeline 接口

```java
public interface Pipeline<T> {
    Mono<T> execute(Msg input);
    Mono<T> execute(Msg input, Class<?> structuredOutputClass);
    default Mono<T> execute();
    default String getDescription();
}
```

Pipeline 是多 Agent 协作的编排层，将多个 Agent 组合成复杂的工作流。

## 五种编排模式

AgentScope 通过示例展示了五种生产级编排模式：

### 模式 1：Supervisor（监督者）

一个协调者 Agent 将请求分派给专业子 Agent：

```
用户请求
  │
  ▼
Supervisor Agent
  ├── 分析请求类型
  ├── 调用 calendar_agent (通过 subAgent 工具)
  ├── 调用 email_agent (通过 subAgent 工具)
  └── 综合回复
```

实现方式：**子 Agent 注册为工具**：

```java
// 创建专业 Agent
ReActAgent calendarAgent = ReActAgent.builder()
    .name("calendar")
    .model(model)
    .toolkit(calendarToolkit)  // 日历工具
    .build();

// 注册到 Supervisor 的 Toolkit
supervisorToolkit.registration()
    .subAgent(calendarAgent, "manage_calendar", "管理日程")
    .register();

supervisorToolkit.registration()
    .subAgent(emailAgent, "send_email", "发送邮件")
    .register();

ReActAgent supervisor = ReActAgent.builder()
    .name("Supervisor")
    .model(model)
    .toolkit(supervisorToolkit)
    .build();
```

**对比 Pokkit**：Pokkit 的 TaskTool 也是 "Agent as Tool" 模式，但固定了 3 个角色（coder/explore/planner），用 AgentConfig 区分。AgentScope 更灵活，任何 Agent 都可以注册为工具。

### 模式 2：Handoff（交接）

Agent 之间通过工具调用实现控制权转移：

```
用户请求
  │
  ▼
Sales Agent ←→ Support Agent
  │                   │
  └── transfer_to_support()
                      └── transfer_to_sales()
```

使用 StateGraph（Spring AI 集成）实现：

```java
// 定义交接工具
public class TransferToSupportTool {
    @Tool(description = "转接到技术支持")
    public String transfer(ToolContext context) {
        context.put("active_agent", "support");
        return "已转接到技术支持";
    }
}

// StateGraph 根据 active_agent 路由
graph.addConditionalEdge("sales_node", state -> {
    if ("support".equals(state.get("active_agent")))
        return "support_node";
    return END;
});
```

### 模式 3：SubAgent（子 Agent 委托）

主 Agent 创建子 Agent 处理子任务，子 Agent 有独立上下文：

```
Orchestrator Agent
  ├── task("explore", "分析代码结构")
  │   └── Explorer Agent（独立 Memory）
  │       ├── 使用 glob/grep 工具
  │       └── 返回结果文本
  ├── task("research", "调研最佳实践")
  │   └── Researcher Agent（独立 Memory）
  │       └── 使用 web_fetch 工具
  └── 综合子 Agent 结果
```

子 Agent 定义方式：
1. **Markdown 文件**（SKILL.md）：用 YAML frontmatter 定义名称、描述、工具
2. **API 编程**：用 ReActAgent.builder() 构建

**对比 Pokkit**：Pokkit 的 TaskTool 就是这种模式的简化版。关键差异：
- Pokkit 子 Agent 用 AgentConfig 预定义，AgentScope 可以动态创建
- Pokkit 排除 "task" 工具防止嵌套，AgentScope 通过工具注册控制
- Pokkit 只返回最后文本，AgentScope 返回完整 Msg（含 metadata）

### 模式 4：Pipeline（管线）

#### 顺序管线

```
SQL Generator → SQL Rater → 输出评分
```

```java
SequentialAgent pipeline = SequentialAgent.builder()
    .agent(sqlGenerator)   // 生成 SQL
    .agent(sqlRater)       // 评价质量
    .build();
```

#### 并行管线

```
               ┌→ Tech Researcher  ──┐
用户问题 ──→  ├→ Finance Researcher ─┤──→ 合并结果
               └→ Market Researcher  ──┘
```

```java
ParallelAgent pipeline = ParallelAgent.builder()
    .agent(techResearcher)
    .agent(financeResearcher)
    .agent(marketResearcher)
    .build();
```

#### 循环管线

```
生成 SQL → 评分 → 分数够高？
  ↑         │        │
  │        NO        YES → 输出
  └────────┘
```

```java
LoopAgent pipeline = LoopAgent.builder()
    .innerAgent(SequentialAgent.builder()
        .agent(sqlGenerator)
        .agent(sqlRater)
        .build())
    .condition(result -> score(result) < 8)  // 继续条件
    .maxIterations(5)
    .build();
```

### 模式 5：Workflow（自定义工作流）

用 StateGraph 定义任意拓扑的工作流：

```
START → query_rewrite → retrieve_docs → prepare_context → agent → END
```

```java
StateGraph graph = new StateGraph(AgentState.class);
graph.addNode("rewrite", rewriteNode);
graph.addNode("retrieve", retrieveNode);
graph.addNode("prepare", prepareNode);
graph.addNode("agent", agentNode);

graph.addEdge(START, "rewrite");
graph.addEdge("rewrite", "retrieve");
graph.addEdge("retrieve", "prepare");
graph.addEdge("prepare", "agent");
graph.addEdge("agent", END);
```

支持条件边、循环、并行分支等任意图结构。

## Routing（动态路由）

根据请求内容动态选择 Agent：

```
用户请求 → 分类 Agent → {
    "github" → GitHub Agent
    "slack"  → Slack Agent
    "notion" → Notion Agent
} → 综合结果
```

两种实现：
1. **简单路由**：分类 → 并行执行匹配的 Agent → 合并
2. **图路由**：StateGraph 中 Router 节点的条件边

## A2A（分布式 Agent 通信）

当 Agent 分布在不同进程/机器上时：

```
Process A: Client Agent
  ├── 通过 A2A 协议调用远程 Agent
  └── 服务发现：Nacos
           │
           ▼
Process B: Server Agent (A2AServer)
  ├── 暴露 Agent 为 A2A 服务
  └── 注册到 Nacos
```

传输层选项：
- HTTP（默认）
- RocketMQ（异步消息）

**对比 Pokkit**：Pokkit 只有进程内的 TaskTool 委托。AgentScope 支持跨进程、跨机器的 Agent 通信。

## Skill（渐进式能力加载）

不一次性给 Agent 所有工具，而是按需加载：

```java
SkillBox skillBox = SkillBox.builder()
    .repository(new ClasspathSkillRepository("skills/"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("SQL Assistant")
    .skillBox(skillBox)  // 注册技能仓库
    .build();
```

Agent 可以：
1. 浏览可用技能列表
2. 用 `read_skill` 工具按需加载具体技能内容
3. 根据加载的技能执行任务

技能用 Markdown 文件定义，带 YAML frontmatter：

```markdown
---
name: sales_analytics
description: 销售数据分析技能
---

## 数据表结构
- customers (id, name, region)
- orders (id, customer_id, amount, date)

## 常见查询模式
...
```
