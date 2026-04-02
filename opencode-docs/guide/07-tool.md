# 07 — Tool 系统

## 工具接口

所有工具都实现 `Tool.Info` 接口：

```typescript
Tool.Info {
  id: string               // 全局唯一标识 (如 "read", "bash", "grep")

  init(ctx?: InitContext) → {
    description: string    // 给 LLM 看的工具描述
    parameters: ZodSchema  // 输入参数的 Zod schema
    execute(args, ctx) → {
      title: string        // 执行结果标题
      metadata?: any       // 元数据
      output: string       // 给 LLM 看的输出
      attachments?: File[] // 附件
    }
    formatValidationError?(error): string  // 自定义验证错误格式
  }
}
```

### 执行上下文 (ToolContext)

```typescript
{
  sessionID: string        // 当前会话 ID
  messageID: string        // 当前消息 ID
  agent: string            // 调用工具的 Agent
  abort: AbortSignal       // 取消信号
  messages: Message[]      // 完整对话历史
  metadata(data)           // 报告执行元数据
  ask(question)            // 向用户提问/请求权限
}
```

## 内置工具列表

### 文件操作
| 工具 | 功能 |
|------|------|
| `read` | 读取文件内容 |
| `write` | 创建/覆盖文件 |
| `edit` | 基于 diff 的文件编辑 |
| `glob` | 模式匹配查找文件 |
| `ls` | 列出目录内容 |
| `grep` | 正则搜索文件内容 |

### 代码操作
| 工具 | 功能 |
|------|------|
| `codesearch` | Exa 代码搜索 |
| `apply_patch` | 应用补丁 |
| `lsp` | LSP 操作 (定义跳转等) |

### 执行
| 工具 | 功能 |
|------|------|
| `bash` | 执行 Shell 命令 |
| `webfetch` | HTTP 请求 |
| `websearch` | Exa 网络搜索 |

### 任务管理
| 工具 | 功能 |
|------|------|
| `task` | 启动子 Agent 任务 |
| `skill` | 调用技能 |
| `todo_write` | 管理待办列表 |
| `batch` | 批量执行多个工具 |

## 工具执行流程

```
LLM 决定调用工具
    │
    ▼
ToolPart 创建 (state: "pending")
    │
    ▼
Permission 检查
    ├─ allow → 继续
    ├─ deny → 返回错误
    └─ ask → 等待用户确认
    │
    ▼
参数验证 (Zod schema)
    │
    ▼
Tool.execute() (state: "running")
    │
    ▼
输出截断检查 (超长输出截断)
    │
    ▼
返回结果 (state: "completed" 或 "error")
    │
    ▼
Snapshot 跟踪 (如果修改了文件)
```

## 工具过滤

不同 Agent 看到不同的工具集：
- `build` — 所有工具
- `plan` — 只读工具 (read, glob, grep, ls, etc.)
- `explore` — 搜索/读取工具
- 自定义 Agent — 通过 permission 配置过滤

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/tool/tool.ts` | Tool.Info 接口定义 |
| `src/tool/` | 所有内置工具实现 |
