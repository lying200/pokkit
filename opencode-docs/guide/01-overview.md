# 01 — 项目总览

## OpenCode 是什么

OpenCode 是一个开源的 AI 编程助手（AI Coding Agent），定位为 Claude Code 的开源替代品。核心特点：

- **100% 开源**（MIT 协议）
- **Provider 无关** — 支持 30+ LLM 提供者（Claude、OpenAI、Gemini、本地模型等）
- **终端优先** — 由 Neovim 用户构建，主打 TUI 体验
- **Client/Server 架构** — 支持远程操作
- **LSP 集成** — 开箱即用的语言服务器支持

当前版本：v1.3.5，仓库地址：github.com/anomalyco/opencode

## 技术栈一览

| 层面 | 技术选型 |
|------|---------|
| 语言 | TypeScript 5.8 |
| 运行时 | Bun 1.3.11+ / Node.js 22+ |
| 包管理 | Bun (workspace) |
| Monorepo | Turborepo 2.8 |
| UI 框架 | SolidJS 1.9 |
| TUI | @opentui/core + @opentui/solid |
| 桌面应用 | Tauri 2 / Electron |
| HTTP 框架 | Hono 4.10 |
| 数据库 | SQLite (Drizzle ORM) |
| 效果系统 | Effect 4.0 beta |
| AI SDK | Vercel AI SDK 6.0 |
| Schema 验证 | Zod 4.1 |
| 构建工具 | Vite 7.1 |
| 代码解析 | Tree-sitter |

## 仓库结构

```
opencode/
├── packages/                    # Monorepo 工作区
│   ├── opencode/               # ⭐ 核心包 (286 TS 文件, ~39k 行)
│   │   ├── src/                # 源码
│   │   ├── migration/          # 数据库迁移
│   │   ├── test/               # 测试
│   │   ├── script/             # 构建脚本
│   │   └── bin/                # CLI 入口
│   │
│   ├── console/                # Web 管理控制台
│   ├── app/                    # 共享 Web UI 组件
│   ├── desktop/                # Tauri 桌面应用
│   ├── desktop-electron/       # Electron 桌面应用
│   ├── ui/                     # SolidJS 组件库
│   ├── plugin/                 # 插件 SDK (@opencode-ai/plugin)
│   ├── sdk/js/                 # JavaScript SDK (客户端/服务端)
│   ├── web/                    # 营销网站 (Astro)
│   ├── docs/                   # 官方文档
│   ├── slack/                  # Slack 集成
│   ├── util/                   # 共享工具库
│   ├── script/                 # 共享构建脚本
│   └── storybook/             # 组件库文档
│
├── infra/                      # 基础设施 (SST)
├── nix/                        # Nix 环境定义
├── specs/                      # 架构规范
├── .github/                    # GitHub Actions
├── AGENTS.md                   # Agent 系统指南 & 代码风格
├── turbo.json                  # Turborepo 配置
├── flake.nix                   # Nix 开发环境
└── package.json                # 根工作区
```

### 核心包内部结构 (`packages/opencode/src/`)

这是学习的重点，包含 42 个主要模块：

```
src/
├── agent/          # 多 Agent 系统 (build/plan/general/explore)
├── cli/            # CLI 命令和 TUI 实现
├── server/         # Hono HTTP/WebSocket 服务
├── session/        # 会话管理 & 消息历史
├── storage/        # 数据库层 (Drizzle/SQLite)
├── provider/       # LLM 提供者集成
├── tool/           # 40+ 内置工具
├── skill/          # 可扩展技能系统
├── plugin/         # 插件系统
├── permission/     # 权限 & 访问控制
├── acp/            # Agent Control Protocol
├── mcp/            # Model Context Protocol
├── lsp/            # Language Server Protocol
├── config/         # 配置管理
├── auth/           # 认证
├── project/        # 项目管理 & 实例上下文
├── effect/         # Effect 服务层
├── bus/            # 事件总线 (PubSub)
├── command/        # 命令注册
├── share/          # 会话分享
├── snapshot/       # 文件快照 (undo 支持)
├── file/           # 文件操作
├── format/         # 代码格式化
├── git/            # Git 集成
└── index.ts        # 主入口
```

## 快速上手命令

```bash
# 安装依赖
bun install

# 开发模式运行
bun dev                    # 在当前目录启动
bun dev .                  # 明确指定仓库根目录
bun dev <目录>             # 针对特定目录

# 类型检查 (必须在包目录下运行)
cd packages/opencode && bun typecheck

# 运行测试 (不能在仓库根目录运行)
cd packages/opencode && bun test

# 构建单平台二进制
packages/opencode/script/build.ts --single
```

## 关键入口文件

| 文件 | 作用 |
|------|------|
| `packages/opencode/src/index.ts` | CLI 主入口，yargs 命令注册 |
| `packages/opencode/bin/opencode` | 可执行文件包装 |
| `packages/opencode/src/cli/cmd/` | 各子命令实现 |
| `packages/opencode/src/server/server.ts` | HTTP/WS 服务端 |
