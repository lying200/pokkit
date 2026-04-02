# 10 — Skill 系统

## 什么是 Skill

Skill 是 Markdown 格式的行为定义，类似于可复用的 Prompt 模板。

## Skill 文件格式

```markdown
---
name: skill-name
description: 这个 Skill 做什么
---

# Skill 内容

这里是给 Agent 的指令，用 Markdown 编写。
支持引用变量、文件等。
```

## 发现路径

Skill 从以下位置自动发现（优先级从高到低）：

1. **外部全局**
   - `~/.claude/skills/*/SKILL.md`
   - `~/.agents/skills/*/SKILL.md`

2. **项目级**
   - `.opencode/skills/*/SKILL.md`
   - `skill/*/SKILL.md`
   - `skills/*/SKILL.md`

3. **Git Worktree**
   - Worktree 根目录下的 skill 目录

## 使用方式

- 在 TUI 中作为命令调用（`/skill-name`）
- 在 Task 工具中被 Agent 调用
- 在自定义 Command 中引用

## 关键源码

| 文件 | 内容 |
|------|------|
| `src/skill/index.ts` | Skill 发现和验证 |
