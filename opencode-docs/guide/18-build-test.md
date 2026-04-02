# 18 — 构建与测试

## Monorepo 结构

使用 Turborepo 管理 20+ 个工作区包：

```bash
# turbo.json 定义构建任务
bun run build          # 构建所有包
bun run typecheck      # 类型检查所有包
```

## 构建流程

### 开发

```bash
bun install            # 安装依赖
bun dev                # 启动核心包开发模式
bun dev:desktop        # Tauri 桌面应用
bun dev:console        # Web 控制台
```

### 生产构建

```bash
# 单平台二进制
packages/opencode/script/build.ts --single

# 多平台构建
./packages/opencode/script/build.ts
```

产出：`packages/opencode/dist/opencode-<platform>/bin/opencode`

支持平台：darwin-arm64, darwin-x64, linux-x64, windows-x64

## 测试

### 重要规则

1. **不能在仓库根目录运行测试** — 有 `do-not-run-tests-from-root` 保护
2. **避免 Mock** — 测试真实实现
3. **不要在测试中复制业务逻辑** — 测试行为而非实现

### 运行测试

```bash
cd packages/opencode
bun test               # 运行所有测试
bun test --watch       # 监听模式
bun test --timeout 30000  # 设置超时
```

### 类型检查

```bash
cd packages/opencode
bun typecheck          # 正确方式
# 不要直接运行 tsc
```

## 代码风格 (摘自 AGENTS.md)

- **单词变量名** — 优先使用单个单词 (`cfg` 而非 `configData`)
- **避免解构** — 用 `obj.a` 而非 `const { a } = obj`
- **const 优先** — 用三元或 early return 代替 `let`
- **避免 else** — 使用 early return
- **避免 try/catch** — 使用 Effect 错误通道
- **Drizzle snake_case** — 数据库字段用 snake_case
- **Bun API 优先** — 如 `Bun.file()` 替代 `fs.readFile()`

## 开发环境

项目提供 Nix flake 定义开发环境：

```bash
# 使用 direnv 自动加载
# .envrc 会加载 flake.nix 定义的环境
```

## 关键文件

| 文件 | 内容 |
|------|------|
| `turbo.json` | Turborepo 配置 |
| `package.json` | 根工作区配置 |
| `flake.nix` | Nix 开发环境 |
| `AGENTS.md` | 代码风格和开发指南 |
| `packages/opencode/test/` | 测试文件 |
