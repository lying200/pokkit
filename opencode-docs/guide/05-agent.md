# 05 — Agent 系统

## 多 Agent 架构

OpenCode 内置了多种专用 Agent，每个有不同的能力和权限：

| Agent | 模式 | 用途 | 权限级别 |
|-------|------|------|---------|
| `build` | primary | 默认 Agent，执行所有编辑操作 | 最高（读写执行） |
| `plan` | primary | 只读分析和规划 | 只读（禁止编辑） |
| `general` | subagent | 研究和多步任务 | 中等 |
| `explore` | subagent | 代码库搜索探索 | 搜索/只读 |
| `compaction` | hidden | 会话摘要（内部使用） | 最小 |

## Agent 配置结构

```typescript
Agent.Info {
  name: string                     // 唯一标识
  mode: "primary" | "subagent" | "all"
  description?: string             // 用于 Agent 路由
  prompt?: string                  // 自定义 System Prompt (覆盖默认)
  model?: {
    providerID: string             // 如 "anthropic"
    modelID: string                // 如 "claude-3-5-sonnet-20241022"
  }
  permission: Ruleset              // 权限规则集
  temperature?: number
  topP?: number
  steps?: number                   // 最大推理步数
  options?: Record<string, any>    // 额外选项
  color?: string                   // TUI 显示颜色
  native?: boolean                 // 是否内置
  hidden?: boolean                 // 是否对用户隐藏
}
```

## 权限规则集 (Ruleset)

每个 Agent 有自己的权限规则，格式为 Glob 模式匹配：

```typescript
Ruleset = {
  [permission_type: string]: {
    [glob_pattern: string]: "allow" | "deny" | "ask"
  }
}
```

示例：

```json
{
  "build": {
    "permission": {
      "read": { "*": "allow" },
      "write": { "*": "allow", "*.env": "ask" },
      "bash": { "*": "ask" },
      "external_directory": { "/tmp/*": "ask" }
    }
  },
  "plan": {
    "permission": {
      "read": { "*": "allow" },
      "write": { "*": "deny" },
      "bash": { "*": "deny" }
    }
  }
}
```

## Agent 路由

Agent 的选择由用户在 TUI 中切换，或者通过 command/skill 中的 `agent` 字段指定。

## 权限合并

Agent 的最终权限由三层合并而来：
1. **默认权限** — 内置 Agent 的默认规则
2. **用户配置** — `opencode.json` 中的 `agent.*.permission`
3. **Agent 专属** — 每个 Agent 自己的权限声明

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/agent/agent.ts` | Agent 定义、默认配置 |
| `src/agent/` | Agent 模块全部 |

## Doom Loop 检测

当同一个工具调用被重复 3 次且输入相同时，系统会触发 "doom loop" 检测，请求用户确认是否继续，防止 Agent 陷入无限循环。
