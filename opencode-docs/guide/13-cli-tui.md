# 13 — CLI 与 TUI

## CLI 入口

主入口文件：`packages/opencode/src/index.ts`

使用 `yargs` 作为命令行解析器，启动流程：

```
1. 注册全局错误处理器 (unhandledRejection, uncaughtException)
2. 初始化日志系统
3. 首次运行时执行数据库迁移 (JSON → SQLite)
4. 设置环境变量 (OPENCODE, AGENT, OPENCODE_PID)
5. 路由到子命令
```

## 可用命令

| 命令 | 功能 |
|------|------|
| `opencode` (默认) | 启动 TUI |
| `opencode run` | 非交互式执行 |
| `opencode serve` | 启动 HTTP/WS 服务 |
| `opencode web` | 启动 Web 界面 |
| `opencode models` | 列出可用模型 |
| `opencode providers` | 管理提供者 |
| `opencode agent` | Agent 配置 |
| `opencode session` | 会话管理 |
| `opencode mcp` | MCP 协议操作 |
| `opencode acp` | ACP 协议操作 |
| `opencode debug` | 调试工具 |
| `opencode generate` | 代码生成 |
| `opencode plugin` | 插件管理 |
| `opencode skill` | 技能管理 |
| `opencode db` | 数据库操作 |
| `opencode export/import` | 会话导出/导入 |
| `opencode github/pr` | GitHub 集成 |
| `opencode upgrade` | 自升级 |
| `opencode stats` | 统计信息 |

## TUI 架构

TUI 使用 SolidJS + @opentui 构建：

- **SolidJS** — 响应式状态管理和渲染
- **@opentui/core** — 终端 UI 基础库
- **@opentui/solid** — SolidJS 绑定

TUI 是一个完整的终端应用，支持：
- 实时流式显示 LLM 输出
- 工具调用状态展示
- 权限确认对话框
- Agent 切换
- 会话管理
- Markdown 渲染 (Marked + Shiki 语法高亮)

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/index.ts` | CLI 主入口 |
| `src/cli/` | CLI 命令和 TUI 实现 |
| `src/cli/cmd/` | 各子命令 |
