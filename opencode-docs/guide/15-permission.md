# 15 — 权限系统

## 权限模型

OpenCode 实现了细粒度的权限控制，确保 AI Agent 的操作安全可控。

### 三种决策

| 决策 | 含义 |
|------|------|
| `allow` | 直接允许 |
| `deny` | 直接拒绝 |
| `ask` | 暂停并询问用户 |

### 规则结构

```typescript
{
  permission: string     // 权限类型 (如 "read", "write", "bash")
  pattern: string        // Glob 模式 (如 "*.env", "/tmp/*")
  action: "allow" | "deny" | "ask"
}
```

## 权限类型

| 类型 | 检查什么 |
|------|---------|
| `read` | 文件读取 |
| `write` | 文件写入 |
| `bash` | Shell 命令执行 |
| `external_directory` | 项目目录外的访问 |
| `doom_loop` | 重复工具调用检测 |
| `question` | Agent 向用户提问 |
| `plan_enter/exit` | 进入/退出 Plan 模式 |

## 用户响应

当权限决策为 `ask` 时，用户有三种选择：

| 响应 | 效果 |
|------|------|
| `once` | 仅本次允许 |
| `always` | 记住并永久允许（存入 SQLite） |
| `reject` | 拒绝 |

## 路径安全检查

- **项目内路径** — 根据 Agent 权限规则检查
- **项目外路径** — 触发 `external_directory` 权限检查
- **Instance.contains(path)** — 判断路径是否在项目/worktree 内

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/permission/index.ts` | 权限服务核心 |
