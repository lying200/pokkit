# 13 — 计划系统 (PlanNotebook)

## 问题

复杂任务（如 "帮我重构这个模块"）需要多步骤执行。没有计划系统的 Agent 容易：
- 忘记已完成的步骤
- 重复执行相同操作
- 偏离原始目标

## PlanNotebook 设计

PlanNotebook 本质是一组**注册为工具**的计划管理操作 + 状态持久化：

### 10 个工具函数

| 工具 | 功能 |
|------|------|
| `create_plan` | 创建新计划（名称、描述、预期结果、子任务列表） |
| `update_plan_info` | 更新计划元信息 |
| `revise_current_plan` | 修改子任务（add/revise/delete） |
| `update_subtask_state` | 更新子任务状态 (todo/in_progress/abandoned) |
| `finish_subtask` | 标记子任务完成 + 记录结果 |
| `view_subtasks` | 查看指定子任务详情 |
| `get_subtask_count` | 获取子任务数量统计 |
| `finish_plan` | 完成/放弃整个计划 |
| `view_historical_plans` | 查看历史计划 |
| `recover_historical_plan` | 恢复历史计划 |

### 数据模型

```
Plan
├── name: String
├── description: String
├── expectedOutcome: String
├── state: PlanState (IN_PROGRESS / DONE / ABANDONED)
├── outcome: String
└── subtasks: List<SubTask>
    ├── name: String
    ├── description: String
    ├── expectedOutcome: String
    ├── state: SubTaskState (TODO / IN_PROGRESS / DONE / ABANDONED)
    └── outcome: String
```

### 使用方式

```java
PlanNotebook planNotebook = PlanNotebook.builder()
    .storage(new InMemoryPlanStorage())
    .maxSubtasks(20)
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Planner")
    .model(model)
    .planNotebook(planNotebook)
    .enablePlan()                    // 启用计划功能
    .build();
```

### 计划上下文注入

PlanNotebook 通过 `PlanToHint` 策略在每次推理前注入当前计划状态：

```
PreReasoningEvent → 检查当前计划 → 注入 Hint 消息：

"当前计划：重构用户模块
子任务：
  1. [DONE] 分析现有代码结构
  2. [IN_PROGRESS] 提取公共接口
  3. [TODO] 实现新的数据访问层
  4. [TODO] 编写测试"
```

Agent 在每次推理时都能看到计划进展，不会偏离目标。

### 状态持久化

PlanNotebook 实现 `StateModule`：

```java
planNotebook.saveTo(session, sessionKey);   // 保存当前计划
planNotebook.loadFrom(session, sessionKey); // 恢复计划
```

配合 Session 持久化，计划可以跨会话恢复。

### 变更 Hook

```java
planNotebook.addChangeHook("sync", (notebook, plan) -> {
    // 计划变更时触发
    // 可用于通知 UI、记录日志等
});
```

## 对比 Pokkit

Pokkit 没有计划系统。对于复杂任务，Agent 只能靠 system prompt 中的指令和对话历史来保持方向。

如果要在 Pokkit 中实现，可以参考 AgentScope 的思路：
1. 创建 PlanTool（包含 create/update/finish 等操作）
2. 在内存中维护计划状态
3. 在每次 LLM 调用前注入计划上下文

关键洞察：**计划就是一组特殊的工具 + 持久化的状态**，不需要修改核心循环。
