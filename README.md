# Pokkit

一个用 Java 从零实现的 AI Coding Agent，学习项目。

参考 [OpenCode](https://github.com/anomalyco/opencode) 的架构思路，用 Spring Boot + Spring AI 做最小实现，
逐步搭建 Agent 的核心能力：agentic loop、tool calling、多 Agent 编排等。

## 技术栈

| 层面 | 选型 |
|------|------|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.0.5 |
| LLM 抽象 | Spring AI 2.0.0-M4 |
| 构建 | Gradle 9 (Kotlin DSL) |
| 开发环境 | devenv + Nix |

## 当前进度

- **v0.1-agentic-loop** — 最小 Agentic Loop
  - `while(true)` 驱动的 LLM → tool call → 确认 → execute → 反馈闭环
  - 工具确认机制：bash 执行前需用户确认 (y/n)
  - 两个内置工具：`bash`（执行命令）、`read`（读文件）
  - 多 Provider 支持：OpenAI / Google GenAI 一键切换
  - [设计文档](docs/01-agentic-loop.md)

- **v0.2-streaming** — 流式输出 + 更多工具 + 安全阀
  - LLM 回复逐 token 实时打印到终端
  - 新增工具：`write`（写文件，需确认）、`glob`（搜索文件）
  - Doom Loop 检测：同一工具连续 3 次相同参数自动停止
  - [设计文档](docs/02-streaming-and-tools.md)

## 快速开始

```bash
# 克隆
git clone https://github.com/lying200/pokkit.git
cd pokkit

# 构建
./gradlew compileJava

# 运行（OpenAI）
OPENAI_API_KEY=sk-xxx ./gradlew bootRun

# 运行（Google Gemini）
POKKIT_PROVIDER=google GOOGLE_AI_API_KEY=xxx ./gradlew bootRun
```

## Provider 切换

通过环境变量 `POKKIT_PROVIDER` 选择 LLM 提供者：

| 值 | 提供者 | 需要的环境变量 |
|----|--------|---------------|
| `openai`（默认） | OpenAI / 兼容 API | `OPENAI_API_KEY`，可选 `OPENAI_BASE_URL`、`OPENAI_MODEL` |
| `google` | Google Gemini | `GOOGLE_AI_API_KEY`，可选 `GOOGLE_AI_MODEL` |

## 项目结构

```
src/main/java/com/pokkit/
├── PokkitApplication.java       # Spring Boot 启动
├── agent/
│   └── AgenticLoop.java         # 核心：agentic loop
├── tool/
│   ├── Tool.java                # 工具接口
│   ├── ToolRegistry.java        # 工具注册表
│   ├── BashTool.java            # 执行 shell 命令
│   └── ReadTool.java            # 读文件内容
└── cli/
    └── Repl.java                # 命令行交互

docs/                            # 每个阶段的设计文档
opencode-docs/                   # OpenCode 源码分析（参考资料）
```

## 阶段记录

每个阶段完成后打 git tag，`docs/` 下有对应的设计文档记录做了什么、为什么这么做、踩了什么坑。

```bash
# 查看所有阶段
git tag

# 回到某个阶段看代码
git checkout v0.1-agentic-loop
```
