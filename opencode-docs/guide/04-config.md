# 04 — 配置系统

## 配置层级

OpenCode 的配置有清晰的优先级层级（从高到低）：

1. **系统级 (admin)** — `/etc/opencode`、`C:\ProgramData\opencode`、`/Library/Application Support/opencode`
2. **用户级** — `~/.opencode/config.jsonc`
3. **项目级** — `.opencode/opencode.json`
4. **环境变量**
5. **默认值**

## 配置文件格式

使用 JSONC（支持注释的 JSON），通过 Zod 进行运行时验证。

```jsonc
// .opencode/opencode.json
{
  // LLM 提供者配置
  "provider": {
    "anthropic": { "apiKey": "sk-..." },
    "openai": { "apiKey": "sk-..." }
  },

  // 默认模型
  "model": {
    "default": "claude-3-5-sonnet-20241022"
  },

  // Agent 配置（覆盖默认）
  "agent": {
    "build": {
      "permission": { /* ... */ }
    }
  },

  // 自定义命令
  "command": {
    "review": {
      "template": "Review this diff: $1",
      "description": "Code review"
    }
  },

  // 工具配置
  "tool": {
    "bash": { "allow": ["npm test"] }
  },

  // 插件
  "plugin": ["@opencode-ai/some-plugin"],

  // 全局指令
  "instructions": ["Always respond in Chinese"],

  // 自定义变量
  "variable": {
    "GITHUB_TOKEN": "ghp_..."
  },

  // 权限规则
  "permission": {
    "read": { "*": "allow" }
  }
}
```

## 关键环境变量

| 变量 | 作用 |
|------|------|
| `OPENCODE_PURE` | 禁用外部插件 |
| `OPENCODE_INSTALL_DIR` | 自定义安装目录 |
| `OPENCODE_BIN_PATH` | 覆盖二进制路径 |
| `OPENCODE_DB` | 自定义数据库路径 |
| `OPENCODE_SERVER_PASSWORD` | Server 认证密码 |
| `OPENCODE_MODELS_URL` | 模型列表 URL |
| `OPENCODE` | 标识 OpenCode 正在运行 |
| `AGENT` | 标识 Agent 模式 |

## 源码关键文件

| 文件 | 内容 |
|------|------|
| `src/config/config.ts` | Config.Info 类型定义，配置加载逻辑 |
| `src/config/` | 配置相关所有模块 |

## 学习建议

1. 阅读 `config.ts` 中的 Zod schema，了解所有可配置项
2. 查看 AGENTS.md 中关于 Drizzle schema 命名的约定（snake_case）
3. 注意配置中的 `variable` 如何在 Provider 和模板中被替换使用
