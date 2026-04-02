# 08 — 快照与时间旅行

## 为什么需要快照

Agent 会修改文件——edit、write、bash 都可能改变磁盘状态。但如果 Agent 改错了，用户需要能**撤销**。

OpenCode 的解决方案：**每个 Step 开始前拍快照，结束后计算 diff，任何时候都能回滚。**

## 实现：基于 Git 的快照系统

**源码**: `packages/opencode/src/snapshot/index.ts`

### 独立的 Git 仓库

快照不用项目自己的 `.git`，而是在 `~/.opencode/data/snapshot/` 下为每个项目维护一个**独立的 Git 仓库**：

```
~/.opencode/data/snapshot/
  └── <project-id>/
      └── <worktree-hash>/
          └── .git/     ← 独立的 Git 仓库
```

工作树 (work-tree) 指向项目目录，但 git-dir 在别处。这样快照操作不会干扰项目的 Git 历史。

### 核心操作

```typescript
// 1. track() — 拍摄快照
//    在每个 step 开始前调用
const hash = await snapshot.track()
// 内部: git add + git write-tree → 返回 tree hash

// 2. patch(hash) — 计算变更
//    在每个 step 结束后调用
const changes = await snapshot.patch(previousHash)
// 内部: git diff-tree → 返回变更文件列表

// 3. restore(hash) — 回滚到快照
//    用户触发 undo 时调用
await snapshot.restore(hash)
// 内部: git read-tree + git checkout-index → 恢复文件

// 4. diff(hash) — 获取完整 diff
//    用于显示变更详情
const diffText = await snapshot.diff(hash)

// 5. diffFull(from, to) — 详细文件变更
//    返回每个文件的 before/after 内容
const fileDiffs = await snapshot.diffFull(fromHash, toHash)
```

### 排除规则

```
排除：
  - 大于 2MB 的文件
  - .git/ 目录本身
  - 符号链接正确处理 (core.symlinks=true)
  - 长路径支持 (core.longpaths=true)
```

## 快照在 Agent 循环中的位置

```
runLoop 迭代
    │
    ▼
Processor.handleEvent()
    │
    ├─ start-step 事件
    │    └→ snapshot.track() → hash₁ (记录 step 开始时的文件状态)
    │
    ├─ [LLM 生成文本，调用工具，修改文件...]
    │
    └─ finish-step 事件
         └→ snapshot.patch(hash₁) → 计算变更
         └→ 创建 PatchPart { hash: hash₁, files: 变更列表 }
```

## PatchPart 数据结构

```typescript
PatchPart {
  type: "patch",
  hash: string,           // 快照 tree hash
  files: [{
    file: string,         // 文件路径
    status: "added" | "modified" | "deleted",
    additions: number,    // 新增行数
    deletions: number,    // 删除行数
  }]
}
```

## 撤销 (Revert)

**源码**: `packages/opencode/src/session/revert.ts`

撤销是**逆向遍历 PatchPart**：

```
消息历史:
  Step 1: PatchPart { hash: h1, files: [a.ts modified] }
  Step 2: PatchPart { hash: h2, files: [a.ts modified, b.ts added] }
  Step 3: PatchPart { hash: h3, files: [c.ts modified] }

用户执行 "undo step 3":
  snapshot.restore(h3) → 恢复到 step 3 开始前的状态
  → c.ts 回到修改前

用户执行 "undo all":
  snapshot.restore(h1) → 恢复到 step 1 开始前的状态
  → a.ts, b.ts, c.ts 全部回到原始状态
```

## 快照 vs Git

| 特性 | 快照系统 | 项目 Git |
|------|---------|---------|
| 存储位置 | ~/.opencode/data/snapshot/ | 项目 .git/ |
| 粒度 | Agent Step 级别 | Commit 级别 |
| 用途 | Agent 操作的 undo | 版本控制 |
| 生命周期 | 随 Session 存在 | 永久 |
| 可见性 | 用户不可见 | 用户管理 |

快照系统**补充**了 Git 而不是替代它——它提供了比 Commit 更细粒度的回滚能力。
