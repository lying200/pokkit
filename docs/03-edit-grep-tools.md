# Pokkit 第三步：Edit + Grep 工具

> **对应 git tag: `v0.3-edit-grep`**

## 为什么做这个

v0.2 的 Agent 能读文件、写文件、搜索文件名、跑命令，但有两个明显短板：

1. **改代码只能整文件覆盖**（write 工具）— 改一行要重写整个文件，容易丢内容
2. **不能搜索文件内容** — 只有 glob 按文件名搜，不能搜 "这个函数在哪被调用了"

加上 edit 和 grep 之后，Agent 的编码闭环完整了：

```
grep（搜内容）→ read（看上下文）→ edit（精确改）→ bash（跑测试）
```

## Edit 工具

### 设计思路

参考 OpenCode 的 edit tool 和 Claude Code 的 Edit tool，核心是 **old_string → new_string 精确替换**。

为什么不做 diff/patch？因为：
- LLM 生成的 diff 格式经常出错（行号偏移、上下文不够）
- 精确字符串匹配对 LLM 来说最容易——直接复制要改的代码，写出新版本
- OpenCode 的 edit tool 也是这个思路，虽然它加了 9 种模糊匹配策略，但我们先做最简单的精确匹配

### 参数

```json
{
  "file_path": "要编辑的文件路径",
  "old_string": "要替换的原始文本（精确匹配）",
  "new_string": "替换后的新文本",
  "replace_all": false
}
```

### 安全机制

- `old_string` 为空 → 创建新文件（和 OpenCode 一致）
- `old_string` 在文件中找不到 → 报错，提示检查空白和缩进
- `old_string` 匹配多处 → 报错，提示提供更多上下文或用 `replace_all=true`
- 需要用户确认才能执行

### 不做什么

| 不做 | 原因 |
|------|------|
| 模糊匹配（空白容忍、缩进灵活等） | 先做精确匹配，够用了 |
| LSP 诊断 | 还没有 LSP 集成 |
| 文件格式化 | 后面再加 |
| 文件锁 | 单线程不需要 |

## Grep 工具

### 设计思路

OpenCode 的 grep 依赖 ripgrep 二进制，我们用纯 Java 实现，省掉外部依赖。
用 `Files.walkFileTree` + Java 正则逐文件逐行匹配。

### 参数

```json
{
  "pattern": "正则表达式",
  "path": "搜索目录（默认当前目录）",
  "include": "文件名 glob 过滤（如 *.java）"
}
```

### 关键决策

- **最多返回 100 条匹配** — 太多了 LLM 也消化不了
- **跳过隐藏目录和大目录**（.git, node_modules, build, target, dist）
- **跳过二进制文件**（.jar, .class, .png, .pdf 等）
- **单行最长 200 字符** — 超长行截断，避免 token 浪费
- **跳过大于 1MB 的文件** — 大概率是二进制或生成文件

### 输出格式

```
Found 3 match(es)

src/main/java/com/pokkit/agent/AgenticLoop.java:38: private static final boolean IS_WINDOWS =
src/main/java/com/pokkit/tool/BashTool.java:16:     private static final boolean IS_WINDOWS =
src/main/java/com/pokkit/tool/BashTool.java:60:     ProcessBuilder pb = IS_WINDOWS
```

## System Prompt 更新

v0.3 更新了 system prompt，告诉 LLM：
- 优先用 edit 而不是 write 来修改已有文件
- 用 grep 搜索代码模式、函数定义、引用

## 当前工具清单

| 工具 | 功能 | 需确认 |
|------|------|--------|
| `bash` | 执行 shell/PowerShell 命令 | 是 |
| `read` | 读文件内容 | 否 |
| `write` | 写文件（整文件覆盖） | 是 |
| `edit` | 精确字符串替换编辑 | 是 |
| `glob` | 按文件名模式搜索 | 否 |
| `grep` | 按内容正则搜索 | 否 |

## 下一步预告

工具基本齐了，接下来大概率会做：
1. **Session 持久化** — SQLite 存储对话历史，重启不丢
2. **消息压缩** — 长对话 token 溢出的处理
