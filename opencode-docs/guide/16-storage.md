# 16 — 存储层

## 数据库

使用 SQLite 作为本地存储，默认路径 `~/.opencode/opencode.db`。

### 关键配置 (Pragmas)

| Pragma | 值 | 作用 |
|--------|---|------|
| journal_mode | WAL | 写前日志，支持并发读写 |
| foreign_keys | ON | 外键约束 |
| cache_size | -64000 | 64MB 缓存 |
| busy_timeout | 5000 | 忙等待 5 秒 |

## ORM: Drizzle

使用 Drizzle ORM 进行类型安全的数据库操作：

```typescript
// Schema 定义 (snake_case，参见 AGENTS.md)
const table = sqliteTable("session", {
  id: text().primaryKey(),
  project_id: text().notNull(),
  created_at: integer().notNull(),
})
```

**命名约定**：字段名使用 snake_case，这样列名不需要额外的字符串映射。

## 主要表

| 表名 | 存储内容 |
|------|---------|
| SessionTable | 会话元数据 |
| MessageTable | 消息 (含 finish reason, cost, tokens) |
| PartTable | 消息 Part (多态) |
| PermissionTable | 权限规则 |
| ProjectTable | 项目元数据 |

## 迁移

使用 Drizzle Kit 管理迁移：

```bash
cd packages/opencode
bun run db generate --name <migration_name>
```

迁移文件位于 `packages/opencode/migration/`。

首次运行时支持从旧的 JSON 格式自动迁移到 SQLite。

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/storage/db.ts` | 数据库连接和配置 |
| `src/session/schema.ts` | 表 Schema 定义 |
| `migration/` | 迁移文件 |
