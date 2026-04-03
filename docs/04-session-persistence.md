# Pokkit 第四步：Session 持久化

> **对应 git tag: `v0.4-session`**

## 为什么做这个

v0.3 的 Agent 工具齐了，但对话历史纯内存——重启程序一切归零。
这对学习项目来说还能忍，但接下来要做消息压缩、权限系统、多 Agent，全都需要持久化做地基。

## 核心设计

### 数据库选型：SQLite

- 零部署，单文件数据库，适合本地工具
- Java 生态有成熟的 `sqlite-jdbc` 驱动
- 纯 JDBC 操作，不引入 JPA/Hibernate/JdbcTemplate，保持简单

### 数据位置

默认 `~/.pokkit/data.db`，可通过系统属性 `pokkit.db` 覆盖。

### Schema

```sql
sessions (
    id          TEXT PRIMARY KEY,    -- UUID
    title       TEXT NOT NULL,       -- 自动生成：第一条消息的前 80 字符
    created_at  TEXT NOT NULL,       -- ISO-8601 时间戳
    updated_at  TEXT NOT NULL        -- 用于排序和恢复最近会话
)

messages (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT REFERENCES sessions(id) ON DELETE CASCADE,
    ordinal         INTEGER NOT NULL,   -- 消息在对话中的位置
    role            TEXT NOT NULL,       -- USER / ASSISTANT / TOOL_RESPONSE
    content         TEXT,               -- 文本内容
    tool_calls      TEXT,               -- JSON：AssistantMessage 的 ToolCall 列表
    tool_responses  TEXT,               -- JSON：ToolResponseMessage 的 ToolResponse 列表
    created_at      TEXT NOT NULL
)
```

关键设计：
- **SystemMessage 不存储**：每次循环动态添加，不属于对话历史
- **多态用列区分**：`role` 字段决定消息类型，`tool_calls` 和 `tool_responses` 按类型只填一个
- **ON DELETE CASCADE**：删 session 自动删关联 messages
- **WAL 模式**：更好的并发读写性能

### 消息序列化

Spring AI 的 Message 是多态的（UserMessage / AssistantMessage / ToolResponseMessage），
不能直接用 Jackson 序列化整个对象。我们手动拆解：

| 消息类型 | role 列 | content 列 | tool_calls 列 | tool_responses 列 |
|----------|---------|------------|---------------|-------------------|
| UserMessage | USER | getText() | null | null |
| AssistantMessage | ASSISTANT | getText() | `[{"id","type","name","arguments"}]` | null |
| ToolResponseMessage | TOOL_RESPONSE | null | null | `[{"id","name","responseData"}]` |

反序列化时根据 `role` 重建对应的 Message 子类。

## REPL 改造

### 启动行为

1. 打开 SQLite 连接
2. 查询最近一个 session → 加载其消息历史
3. 若无 session → 自动新建
4. 显示会话标题和已加载消息数

### 新增命令

| 命令 | 功能 |
|------|------|
| `/new` | 新建会话，清空内存历史 |
| `/history` | 列出所有会话（id 前缀、标题、时间、消息数） |
| `/clear` | 删除所有会话数据，重新开始 |
| `/help` | 显示命令帮助 |

### 消息保存时机

每次 `AgenticLoop.run()` 返回后，比较 history 的 size 变化，增量保存新消息。
这样即使中途崩溃，也只丢失最后一轮对话。

### 会话标题

取第一条用户消息的前 80 字符，截断在词边界。

## AgenticLoop 不变

AgenticLoop 仍然接收 `List<Message>`，完全不知道持久化的存在。
Repl 在外层处理加载/保存，职责分离干净。

## 代码结构

```
src/main/java/com/pokkit/
├── session/
│   ├── Session.java              # record：会话数据
│   ├── MessageSerializer.java    # Message ↔ DB 列的转换
│   └── SessionRepository.java   # 纯 JDBC 操作
├── agent/
│   └── AgenticLoop.java          # 不变
├── tool/
│   └── ...                       # 不变
└── cli/
    └── Repl.java                 # 加入 session 管理 + 命令分发
```

## 踩过的坑

### AssistantMessage 构造函数

Spring AI 2.0.0-M4 的 `AssistantMessage` 有两个构造函数：
- `AssistantMessage(String content)` — 只接受文本
- `AssistantMessage(String, Map, List<ToolCall>, List<Media>)` — protected

反序列化时需要带 ToolCall 列表，必须用 `AssistantMessage.builder().content(...).toolCalls(...).build()`。

## 不做什么

| 不做 | 原因 |
|------|------|
| 会话切换（/load）| 先做最简单的：新建和清除 |
| 消息编辑/删除 | 不需要 |
| 会话搜索 | 后面再说 |
| 消息压缩 | 下一个版本 |

## 下一步预告

持久化就位后，下一步大概率是：
1. **消息压缩 / Compaction** — 长对话 token 溢出的处理
2. **权限系统** — 持久化用户的 allow/deny 选择
