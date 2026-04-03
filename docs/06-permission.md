# Pokkit 第六步：权限系统

> **对应 git tag: `v0.6-permission`**

## 为什么做这个

v0.5 之前的"权限"就是 `tool.requiresConfirmation()` + 每次弹 y/n。
每次执行 bash/edit/write 都要手动确认，重复操作很烦。
而且权限逻辑写死在工具里，无法统一管理，也无法为后续的多 Agent 做差异化权限。

## 从 OpenCode 学到了什么

OpenCode 的权限系统在 `packages/opencode/src/permission/`，核心设计：

### 三值逻辑

每条规则的 action 是 `"allow" | "deny" | "ask"` 三选一：
- `allow`：直接放行，用户无感
- `deny`：直接拒绝，工具收到拒绝消息
- `ask`：询问用户，用户可以选择 once / always / reject

### Rule 结构

```typescript
Rule = { permission: string, pattern: string, action: Action }
```
- `permission`：权限类型（read, edit, bash, external_directory 等）
- `pattern`：glob 模式匹配文件路径（`*.env`, `/tmp/*`, `*`）
- `action`：三值之一

### 规则评估：Last Match Wins

```typescript
const match = rules.findLast(
    rule => Wildcard.match(permission, rule.permission) && 
            Wildcard.match(pattern, rule.pattern)
)
return match ?? { action: "ask" }  // 默认 ask
```

多组规则合并后从后往前找第一个匹配的。后面的规则覆盖前面的。

### 用户响应

- `once`：仅此次允许
- `always`：添加一条 allow 规则到 session，后续自动放行
- `reject`：拒绝，可附带反馈文本

### 两级存储

- Project 级（PermissionTable）：跨 session 持久化
- Session 级（SessionTable.permission）：单次会话内有效

### 按 Agent 差异化

不同 Agent 有不同的默认规则集：
- `build` agent：全权限
- `plan` agent：只读，edit 被 deny
- `explore` agent：搜索和读取，edit/write 被 deny

## Pokkit 的实现

提取 OpenCode 的核心：三值逻辑 + last match wins + always 记忆。

### 简化点

| OpenCode | Pokkit |
|----------|--------|
| permission + pattern 两维匹配 | 只按工具名一维匹配 |
| Glob 文件路径模式 | 不支持文件路径级控制 |
| Project 级 + Session 级两层存储 | 仅 Session 级 |
| Deferred 异步等待用户响应 | 同步 Scanner 输入 |
| 每个 Agent 独立权限集 | 全局统一（后续多 Agent 时扩展） |

### 默认规则

```java
List<Rule> DEFAULTS = List.of(
    new Rule("read", ALLOW),   // 读文件：直接放行
    new Rule("glob", ALLOW),   // 搜索文件名：直接放行
    new Rule("grep", ALLOW),   // 搜索内容：直接放行
    new Rule("bash", ASK),     // 执行命令：需确认
    new Rule("write", ASK),    // 写文件：需确认
    new Rule("edit", ASK),     // 编辑文件：需确认
    new Rule("*", ASK)         // 未知工具：需确认
);
```

### 用户交互

```
[permission] allow bash: {"command":"ls"}? (y=once/a=always/n=reject): a
[permission] bash will be auto-approved for this session
```

选 `a` 后，`Rule("bash", ALLOW)` 被追加到 sessionRules，
后续 bash 调用时 last match wins 命中这条新规则，直接放行。

### 权限持久化

session 级 always 规则存入 SQLite 的 `sessions.permissions` 列（JSON）。
恢复 session 时一起加载，`/new` 时重置。

### 架构改动

权限逻辑从工具中抽离，统一到 PermissionService：
- **移除** `Tool.requiresConfirmation()` 方法
- **移除** `AgenticLoop.askConfirmation()` 方法
- **新增** `PermissionService.check()` 在工具执行前统一检查

## OpenCode 做了但我们简化掉的

### 文件路径级的 Glob 匹配

OpenCode 的规则有 `pattern` 维度，支持 `*.env` → deny, `/tmp/*` → allow 这样的文件路径匹配。
比如可以规定"编辑 .env 文件需要确认，但编辑 src/ 下的代码自动放行"。

**生产影响**：无法做到"允许编辑代码但禁止编辑配置文件"这种细粒度控制。

### Bash 命令内容扫描

OpenCode 的 bash 工具会扫描命令内容，提取其中涉及的目录和危险操作模式，
分别请求 `external_directory` 和 `bash` 权限。比如 `rm -rf /` 会触发特殊的权限检查。

**生产影响**：用户 always 了 bash 后，所有命令都会执行，包括危险命令。

### Project 级持久化

OpenCode 有独立的 `PermissionTable`（project_id 为主键），存储跨 session 的权限规则。
用户在一个 session 中 always 的规则，新 session 中也会生效。

**生产影响**：每次新建会话都需要重新 always 常用工具。

### 用户拒绝时的纠正反馈

OpenCode 支持 `CorrectedError`——用户拒绝时可以附带文本反馈（比如"不要用 rm，用 trash"），
这个反馈会作为工具结果返回给 LLM。

**生产影响**：用户只能拒绝，不能告诉 LLM 应该怎么做。

### 权限请求的批量处理

OpenCode 用 `Deferred` 异步模式，多个工具调用的权限请求可以同时 pending，
用户 always 一个后会自动检查并批准其他匹配的 pending 请求。

**生产影响**：我们是同步逐个处理，无法批量审批。

### 权限类型语义分组

OpenCode 将 edit/write/apply_patch/multiedit 都映射到 `"edit"` 权限类型，
用户 always 一个就全部生效。我们按工具名精确匹配，always edit 不会自动 always write。

**生产影响**：用户需要分别 always 每个写操作工具。

## 下一步预告

权限系统就位后，可以开始做：
1. **多 Agent（Task 工具）** — 不同 Agent 不同权限集，子 Agent 并行工作
