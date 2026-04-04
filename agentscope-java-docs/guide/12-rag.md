# 12 — RAG 知识库

## Knowledge 接口

```java
public interface Knowledge {
    Mono<Void> addDocuments(List<Document> documents);
    Mono<List<Document>> retrieve(String query, RetrieveConfig config);
}
```

### Document 模型

```java
public class Document {
    private String id;
    private String text;
    private DocumentMetadata metadata;
    private float[] embedding;
    private double score;        // 检索相关性分数
}
```

### RetrieveConfig

```java
RetrieveConfig config = RetrieveConfig.builder()
    .limit(10)                   // 最多返回 10 条
    .scoreThreshold(0.7)         // 相关性阈值
    .build();
```

## 两种注入模式

### 模式 1：自动注入 (RAGMode.GENERIC)

通过 `GenericRAGHook` 自动在推理前注入检索结果：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledgeBase(knowledge)           // 注册知识库
    .ragMode(RAGMode.GENERIC)           // 自动注入模式
    .build();
```

Hook 在 `PreReasoningEvent` 时自动：
1. 从最后一条用户消息提取查询
2. 调用 `knowledge.retrieve(query, config)`
3. 将检索结果注入到系统消息中

### 模式 2：Agent 控制 (RAGMode.AGENT_CONTROL)

注册检索工具，让 Agent 自己决定何时检索：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledgeBase(knowledge)
    .ragMode(RAGMode.AGENT_CONTROL)     // Agent 自主检索
    .build();
```

框架自动注册 `KnowledgeRetrievalTools`，Agent 可调用 `search_knowledge` 工具。

## RAG 后端选项

### 自建 RAG (agentscope-extensions-rag-simple)

最灵活的选项，支持多种向量数据库：

| 向量数据库 | 依赖 |
|-----------|------|
| Qdrant | qdrant-client |
| Milvus | milvus-sdk-java |
| PostgreSQL + pgvector | postgresql-jdbc |
| Elasticsearch | elasticsearch-client |

文档处理能力：
- PDF (Apache PDFBox)
- Word (Apache POI)
- 通用文档 (Apache Tika)
- 文本分片 (TextSplitter)

嵌入生成：
- DashScope Embeddings
- OpenAI Embeddings

### 百炼知识库 (agentscope-extensions-rag-bailian)

阿里云托管知识库服务，免运维。

### Dify (agentscope-extensions-rag-dify)

开源 RAG 平台集成。

### RagFlow (agentscope-extensions-rag-ragflow)

阿里 RAG 框架集成。

### Haystack (agentscope-extensions-rag-haystack)

开源 RAG 框架集成。

## 对比 Pokkit

Pokkit 没有 RAG 能力。如果要添加，有两种路径：

**简单路径**：在 AgenticLoop 中硬编码检索逻辑
**推荐路径**：先实现 Hook 系统，然后 RAG 变成一个 PreReasoningEvent Hook

AgentScope 的 RAG 设计启示：
- Knowledge 接口很简单（retrieve + addDocuments），核心抽象不复杂
- 复杂性在后端实现（嵌入、向量存储、分片）
- 注入逻辑通过 Hook 与核心解耦
