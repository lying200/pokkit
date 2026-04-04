# 08 — 记忆与状态

## 三层架构

```
┌────────────────────────────────────────────────────┐
│  Layer 3: Session 持久化                            │
│  Session 接口 → JsonSession / Redis / MySQL         │
│  用途：跨进程恢复 Agent 状态                         │
├────────────────────────────────────────────────────┤
│  Layer 2: Long-Term Memory (LTM)                   │
│  LongTermMemory 接口 → Mem0 / Bailian / SimpleRAG  │
│  用途：跨会话记住用户偏好和历史                       │
├────────────────────────────────────────────────────┤
│  Layer 1: Short-Term Memory                        │
│  Memory 接口 → InMemoryMemory                      │
│  用途：当前对话历史                                  │
└────────────────────────────────────────────────────┘
```

## Layer 1: 短期记忆 (Memory)

### Memory 接口

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

### InMemoryMemory 实现

```java
public class InMemoryMemory implements Memory {
    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return messages.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // StateModule 实现
    @Override
    public void saveTo(Session session, SessionKey key) {
        session.save(key, "memory_messages", new ArrayList<>(messages));
    }

    @Override
    public void loadFrom(Session session, SessionKey key) {
        List<Msg> loaded = session.getList(key, "memory_messages", Msg.class);
        messages.clear();
        messages.addAll(loaded);
    }
}
```

使用 `CopyOnWriteArrayList` 确保线程安全（读多写少场景）。

**对比 Pokkit**：Pokkit 用 `List<Message>` 直接传入 `AgenticLoop.run()`，没有 Memory 抽象。这意味着：
- 记忆管理和循环逻辑耦合
- 无法替换记忆实现
- 无法做滑动窗口等策略

## Layer 2: 长期记忆 (LongTermMemory)

### 接口

```java
public interface LongTermMemory {
    Mono<Void> record(List<Msg> msgs);   // 记录对话
    Mono<String> retrieve(Msg msg);       // 检索相关记忆
}
```

### 三种工作模式

```java
public enum LongTermMemoryMode {
    STATIC_CONTROL,  // 框架自动 record/retrieve
    AGENT_CONTROL,   // Agent 通过工具主动调用
    BOTH             // 两者兼有
}
```

**STATIC_CONTROL**：
- 通过 `StaticLongTermMemoryHook` 自动运行
- PreReasoningEvent 时自动 retrieve，注入到消息中
- PostCallEvent 时自动 record 对话

**AGENT_CONTROL**：
- 注册 `LongTermMemoryTools` 到 Toolkit
- Agent 自主决定何时 record/retrieve

**BOTH**：
- 自动 + Agent 主动，两者兼有

### 后端实现

| 实现 | 模块 | 特点 |
|------|------|------|
| Mem0 | agentscope-extensions-mem0 | SaaS 服务，语义检索 |
| Bailian | agentscope-extensions-rag-bailian | 阿里云知识库 |
| SimpleRAG | agentscope-extensions-rag-simple | 自建向量数据库 |
| ReMe | agentscope-extensions-reme | 情景记忆 |

### 使用示例

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .longTermMemory(mem0Client)
    .longTermMemoryMode(LongTermMemoryMode.BOTH)
    .build();
```

**对比 Pokkit**：Pokkit 没有长期记忆。每次新 Session 从零开始。

## Layer 3: Session 持久化

### StateModule 接口

所有可持久化的组件实现此接口：

```java
public interface StateModule {
    default void saveTo(Session session, SessionKey key);
    default void loadFrom(Session session, SessionKey key);
    default boolean loadIfExists(Session session, SessionKey key);
}
```

实现者：Memory、Toolkit（activeGroups）、PlanNotebook、Agent 自身。

### Session 接口

```java
public interface Session {
    void save(SessionKey key, String field, State value);
    void save(SessionKey key, String field, List<? extends State> values);
    <T extends State> Optional<T> get(SessionKey key, String field, Class<T> type);
    <T extends State> List<T> getList(SessionKey key, String field, Class<T> itemType);
    boolean exists(SessionKey key);
    void delete(SessionKey key);
    void close();
}
```

### Session 实现

| 实现 | 特点 | 适用场景 |
|------|------|---------|
| `InMemorySession` | 内存中，进程结束丢失 | 测试、一次性任务 |
| `JsonSession` | JSON 文件持久化 | 单机部署 |
| Redis Session | Redis 集群存储 | 分布式部署 |
| MySQL Session | 关系数据库存储 | 需要事务保证 |

### JsonSession 存储策略

- 单值：全量替换
- 列表：增量追加（优化大消息列表的写入性能）

### ReActAgent 的状态保存

```java
// ReActAgent.saveTo()
agent.saveTo(session, sessionKey);

// 保存内容取决于 StatePersistence 配置：
// 1. AgentMetaState — 总是保存 (id, name, description, sysPrompt)
// 2. Memory messages — 如果 memoryManaged = true
// 3. Toolkit activeGroups — 如果 toolkitManaged = true
// 4. PlanNotebook state — 如果 planNotebookManaged = true
```

**对比 Pokkit**：

| 维度 | AgentScope | Pokkit |
|------|-----------|--------|
| 存储后端 | 可插拔（Memory/Redis/MySQL/JSON） | SQLite 硬编码 |
| 持久化范围 | Agent 元数据 + Memory + Toolkit + Plan | 消息 + 权限规则 |
| 恢复能力 | 完整恢复 Agent 到之前状态 | 只恢复对话历史 |
| 列表写入 | 增量追加（JsonSession） | 全量替换或逐条插入 |
| 分布式 | Redis Session 原生支持 | 不支持 |

## 上下文压缩

AgentScope 的上下文压缩通过扩展模块实现：

### AutoContextMemory (扩展模块)

`agentscope-extensions-autocontext-memory` 提供自动上下文压缩：

- 自动摘要旧对话
- Token 用量优化
- 滑动窗口 + 摘要保留

**对比 Pokkit**：Pokkit 的 `MessageCompactor` 有类似功能（两层压缩：裁剪 + 摘要），但内置在核心代码中。AgentScope 把它做成扩展模块，可选使用。
