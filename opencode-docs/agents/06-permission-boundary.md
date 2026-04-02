# 06 — 权限即边界

## 核心思想

> 权限不是"安全功能"，而是 **Agent 能力的定义**。

同一个 LLM 模型，配上不同的权限集，就变成完全不同的 Agent：

```
Claude + { edit: allow, bash: allow } = Build Agent (全能)
Claude + { edit: deny, bash: deny }  = Plan Agent (只读)
Claude + { edit: deny, bash: allow, task: deny } = Explore Agent (搜索)
```

权限决定了 LLM 能"看到"哪些工具（看不到就不会调用），能执行哪些操作，从而塑造了 Agent 的行为模式。

## 权限评估模型

### 三值逻辑

```
evaluate(permission, pattern, ruleset) → "allow" | "deny" | "ask"
```

不是二值的 allow/deny，而是**三值**。`ask` 引入了人在循环中 (Human-in-the-Loop)。

### 规则匹配

规则使用 Wildcard（glob 模式）匹配：

```typescript
// 规则
{ permission: "read", pattern: "*.env", action: "ask" }

// 匹配
evaluate("read", ".env")          → "ask"  ✓ 匹配
evaluate("read", ".env.example")  → "ask"  ✓ 匹配
evaluate("read", "src/main.ts")   → (不匹配此规则)
```

多条规则时，**更具体的规则优先**（或按定义顺序）。

### 工具到权限的映射

不是每个工具一个权限——有语义合并：

```
edit, write, apply_patch, multiedit → 权限类型 "edit"
read                               → 权限类型 "read"
bash                               → 权限类型 "bash"
task                               → 权限类型 "task"
其他工具                            → 权限类型 = 工具名
```

所以禁止 "edit" 权限会同时禁掉 edit、write、apply_patch、multiedit 四个工具。

## 权限的三层合并

Agent 实际执行时的权限来自三层合并：

```
默认 Agent 权限 (代码中硬编码)
    ↓ 被覆盖
用户配置权限 (opencode.json → agent.build.permission)
    ↓ 被合并
Session 级权限 (运行时动态添加)
    ↓
最终权限 = Permission.merge(agent.permission, session.permission)
```

## "ask" 的交互流程

当权限为 `ask` 时，整个 Agent 循环**暂停**：

```
Agent 调用 bash("rm -rf /tmp/old")
    │
    ▼
Permission.evaluate("bash", "rm -rf /tmp/old", ruleset) → "ask"
    │
    ▼
Bus.publish("permission.asked", { ... })
    │
    ▼
TUI 显示确认对话框
    │
    ├─ 用户选 "once"    → 本次允许，Agent 继续
    ├─ 用户选 "always"  → 写入 DB，以后同类操作自动允许
    └─ 用户选 "reject"  → Agent 收到 CorrectedError
         │
         ▼
    LLM 看到: "Tool execution denied: [用户的反馈文字]"
    LLM 可以据此调整策略
```

**"always" 的持久化**：用户的 "always" 选择写入 SQLite 的 PermissionTable，在后续会话中依然有效。

## 权限如何定义子 Agent

Task 工具创建子 Agent 时的权限约束：

```typescript
// 父 Agent (build) 的权限
{ task: { "*": "allow" }, todowrite: { "*": "allow" }, edit: { "*": "allow" } }

// 检查子 Agent 是否被允许
if (Permission.evaluate("task", agentName, parent.permission).action === "deny") {
  // 这个子 Agent 不可用
}

// 子 Agent 继承但受限
// 如果父 Agent 没有 task 权限 → 子 Agent 也没有（防止嵌套）
// 如果父 Agent 没有 todowrite → 子 Agent 也没有
```

## 外部目录权限

```
项目目录: /home/user/my-project/
    │
    ├─ /home/user/my-project/src/main.ts → 正常权限检查
    │
    └─ /etc/hosts → 触发 "external_directory" 权限检查
         │
         ▼
    Permission.evaluate("external_directory", "/etc/hosts", ruleset)
```

`Instance.contains(path)` 判断路径是否在项目目录内。项目外的路径自动触发额外权限检查。

## 权限类型汇总

| 权限类型 | 检查目标 | 典型配置 |
|---------|---------|---------|
| `read` | 文件读取路径 | `{ "*": "allow", "*.env": "ask" }` |
| `edit` | 文件写入路径 | `{ "*": "allow" }` (build), `{ "*": "deny" }` (plan) |
| `bash` | Shell 命令 | `{ "*": "ask" }` |
| `task` | 子 Agent 名称 | `{ "*": "allow" }` (build), `{ "*": "deny" }` (explore) |
| `external_directory` | 项目外路径 | `{ "*": "ask" }` |
| `doom_loop` | 工具名 | `{ "*": "ask" }` |
| `question` | Agent 提问 | `{ "*": "allow" }` (build), `{ "*": "deny" }` (explore) |
| `plan_enter/exit` | 模式切换 | `{ "*": "allow" }` |
