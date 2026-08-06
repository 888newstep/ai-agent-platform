# AI Agent Platform 数据链路文档

## 目录

- [1. 普通聊天链路](#1-普通聊天链路)
- [2. ReAct 推理链路](#2-react-推理链路)
- [3. 多智能体协作链路](#3-多智能体协作链路)
- [4. 文档入库链路](#4-文档入库链路)
- [5. RAG 检索链路](#5-rag-检索链路)
- [6. 流式聊天链路](#6-流式聊天链路)
- [7. 认证与鉴权链路](#7-认证与鉴权链路)
- [8. 缓存架构](#8-缓存架构)
- [9. 弹性容错链路](#9-弹性容错链路)
- [10. 可观测性与指标链路](#10-可观测性与指标链路)
- [11. 电商知识库导入链路](#11-电商知识库导入链路)
- [12. 客服数据导入链路](#12-客服数据导入链路)

---

## 1. 普通聊天链路

### 数据流向

```
HTTP Request -> AiAgentController.chat()
                        |
                  AiAgentService.chat()
                        |
                  SemanticCacheService.getIfCached()
                        | (未命中)
                  MultiRecallService.search()  <- RagCacheService 缓存层
                        |
                  LongContextManager.getOptimizedContext()
                        |
                  ChatLanguageModel.generate()  <- ResilientChatLanguageModel -> FallbackChatLanguageModel
                        |
                  SemanticCacheService.put()
                  LongContextManager.saveMessageAndMaybeSummarize()
                  PlatformMetricsService.recordChat()
                        |
                  HTTP Response
```

### 关键方法

#### 1.1 入口层

**类**: com.aiagent.agent.api.AiAgentController

```java
@PostMapping("/chat")
public ResponseEntity<Map<String, Object>> chat(
    @RequestParam String sessionId,
    @RequestParam String question,
    @RequestParam(defaultValue = "true") boolean useRag)
```

- 接收 HTTP POST 请求
- 参数：sessionId（会话ID）、question（用户问题）、useRag（是否启用RAG，默认 true）
- 返回：{sessionId, question, answer, mode:"normal"}
- 调用 AiAgentService.chat()

**控制器其他端点**:

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/agent/session | 创建会话，返回 {sessionId} |
| DELETE | /api/v1/agent/session/{sessionId} | 清除会话 |
| POST | /api/v1/agent/react/chat | ReAct 推理聊天 |
| POST | /api/v1/agent/multi-agent/execute | 多智能体协作 |
| GET | /api/v1/agent/chat/stream | SSE 流式聊天 |
| POST | /api/v1/agent/document/upload | 异步上传文档（返回 202） |
| GET | /api/v1/agent/document/{documentId}/status | 查询文档处理状态 |
| POST | /api/v1/agent/document/search | 相似文档搜索 |
| DELETE | /api/v1/agent/cache | 清除语义缓存 |
| POST | /api/v1/agent/evaluate | RAG 效果评估 |
| GET | /api/v1/agent/health | 健康检查 |

#### 1.2 服务层

**类**: com.aiagent.agent.application.AiAgentService

```java
public String chat(String sessionId, String question, boolean useRag)
```

**依赖注入**: ChatLanguageModel, StreamingChatLanguageModel, AiProperties, ReActAgent, SemanticCacheService, MultiRecallService, LongContextManager, PlatformMetricsService

**执行步骤**:

1. **语义缓存检查**
   ```java
   String cached = semanticCacheService.getIfCached(question);
   ```
   - 问题 -> embedding -> 遍历 Redis 缓存索引 -> 余弦相似度 >= 0.92 -> 命中返回

2. **RAG 上下文构建**（useRag=true 时）
   ```java
   String context = buildContextFromMultiRecall(question);
   ```
   - 调用 MultiRecallService.search(question, topK)
   - 返回 topK 个相关文档片段

3. **会话上下文构建**
   ```java
   String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
   ```
   - 获取滑动窗口最近消息（默认10轮）
   - 检索相关历史摘要（每5轮生成一次，取 top 3 条）

4. **Prompt 构建**
   ```java
   String fullPrompt = buildPrompt(optimizedHistory, context, question);
   ```
   - 使用 PromptTemplate，模板包含 {{context}} 和 {{question}} 占位符
   - 指示模型使用中文回答

5. **模型调用**
   ```java
   String response = chatLanguageModel.generate(fullPrompt);
   ```
   - 调用链：ResilientChatLanguageModel -> FallbackChatLanguageModel -> 实际模型
   - 根据配置选择 DeepSeek/通义千问/豆包/Qwen3 Flash/本地模型
   - 带 Fallback 机制（主模型失败 -> 自动切本地 Ollama）
   - Resilience4j 熔断器 + 重试保护

6. **缓存回写与指标记录**
   ```java
   semanticCacheService.put(question, response);
   longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
   longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
   platformMetricsService.recordChat(mode, useRag, cacheHit, success, sample);
   ```
   - 写入语义缓存（TTL 24小时）
   - 追加到会话历史
   - 每5轮触发摘要生成
   - 记录聊天指标（延迟、命中率等）

**内部接口**:
```java
interface Assistant {
    TokenStream chat(String message);
}
```

#### 1.3 语义缓存层

**类**: `com.aiagent.infrastructure.cache.SemanticCacheService`

```java
public String getIfCached(String question)
public void put(String question, String answer)
```

**核心逻辑**:

- **Redis Key 结构**:
  - 缓存前缀：`ai:semantic-cache:`
  - 索引集合：`ai:semantic-cache:index`（存储所有缓存 key 的 Set）

- **getIfCached()**:
  1. 问题 -> embedding（优先从 `EmbeddingCacheService` 获取）
  2. 遍历 Redis 中所有缓存条目
  3. 计算余弦相似度：`cosineSimilarity(queryEmbedding, cachedEmbedding)`
  4. 阈值 >= 0.92 -> 命中返回

- **put()**:
  1. 问题 -> embedding
  2. 生成缓存 Key：`ai:semantic-cache:{md5_hash}`
  3. 存入 Redis：`{question, answer, embedding, timestamp}`
  4. 添加到索引集合，TTL 24小时

- **clear()**: 删除所有缓存条目和索引

#### 1.4 会话管理层

**类**: `com.aiagent.infrastructure.memory.LongContextManager`

```java
public String getOptimizedContext(String sessionId, String question)
public void saveMessageAndMaybeSummarize(String sessionId, String role, String content)
```

**核心逻辑**:

- **Redis Key 结构**:
  - 历史消息：`ai:history:{sessionId}`（List）
  - 历史摘要：`ai:summary:{sessionId}`（List）

- **常量**:
  - `SLIDING_WINDOW_SIZE = 10`（滑动窗口大小）
  - `SUMMARY_INTERVAL = 5`（每5轮触发摘要）
  - `SUMMARY_RETRIEVE_TOP_K = 3`（摘要检索条数）

- **getOptimizedContext()**:
  1. 获取滑动窗口最近消息
  2. 检索相关历史摘要（关键词匹配评分）
  3. 拼接返回

- **saveMessageAndMaybeSummarize()**:
  1. 追加消息到 Redis 列表
  2. 超出窗口大小 -> 丢弃最早消息（LTRIM）
  3. 每5轮触发 `generateAndStoreSummary()`
  4. 摘要存储在 Redis List 中（最多20条）

- **generateAndStoreSummary()**:
  1. 取最近 `SUMMARY_INTERVAL` 条消息
  2. 调用 LLM 压缩为 <=200 字摘要
  3. 存储 `{summary, timestamp, round}` 到 Redis

- **retrieveRelevantSummaries()**:
  1. 对问题分词（按空白/标点分割，过滤 >= 2 字符）
  2. 关键词匹配评分
  3. 取 top-K 条摘要

- **clearSession()**: 删除历史和摘要 key

---

## 2. ReAct 推理链路

### 数据流向

```
HTTP Request -> AiAgentController.reactChat()
                        |
                  AiAgentService.reactChat()
                        |
                  SemanticCacheService.getIfCached()
                        | (未命中)
                  MultiRecallService.search()
                  LongContextManager.getOptimizedContext()
                        |
                  ReActAgent.execute(question, context, history)
                        |
                  +---------------------------------------------+
                  | Thought -> Action -> Observation 循环        |
                  | (最多 MAX_STEPS=10 步)                       |
                  |                                              |
                  | 1. LLM.generate(systemPrompt + prompt)       |
                  | 2. 正则匹配 Final Answer -> 返回             |
                  | 3. 正则匹配 Action -> 执行工具               |
                  | 4. 追加 Observation -> 下一轮                |
                  | 5. 重复 Observation 检测 -> 提前终止         |
                  | 6. 超时检测（TIMEOUT=3min）                  |
                  +---------------------------------------------+
                        |
                  ToolService.executeTool()
                        |
                  HTTP Response
```

### 关键方法

#### 2.1 ReAct 代理

**类**: `com.aiagent.agent.application.ReActAgent`

```java
public String execute(String question, String context)
public String execute(String question, String context, String history)
```

**核心参数**:
- `MAX_STEPS = 10`（最大推理步数）
- `TIMEOUT = 3min`（超时时间）
- `MAX_REPEATED_OBSERVATIONS = 3`（重复观察阈值）

**正则模式**:
- `ACTION_PATTERN`: 匹配 `Action: tool_name("input")`
- `FINAL_ANSWER_PATTERN`: 匹配 `Final Answer: ...`
- `THOUGHT_PATTERN`: 匹配 `Thought: ...`

**执行流程**:
1. 构建系统提示（包含工具描述）
2. 构建用户提示（包含历史、上下文、问题）
3. 循环执行：
   - 超时检查
   - LLM 生成
   - 正则匹配 Final Answer -> 返回结果
   - 正则匹配 Action -> 执行工具
   - 追加 Observation
   - 重复观察检测（连续 >= 3 次相同观察 -> 提前终止）

#### 2.2 工具服务

**类**: `com.aiagent.agent.infrastructure.tool.ToolService`

**工具方法**（`@Tool` 注解）:

1. **queryDatabase(String sql)**
   - 安全检查：必须是 SELECT 语句
   - 禁止关键词：`insert, update, delete, drop, alter, truncate, create, grant, revoke, merge, call, execute, for update`
   - 表名白名单验证（从 `ai.tool.database-query.allowed-tables` 读取）
   - 自动添加 `LIMIT maxRows`（默认 100）
   - 使用 `PreparedStatement` 只读执行

2. **callExternalApi(String url, String method, String body)**
   - 方法白名单：`GET, POST`
   - URI 验证：scheme 必须 http/https
   - 主机白名单验证（支持 `*.domain` 通配符）
   - 拒绝私有/回环地址（`InetAddress` 检查）
   - 超时控制（默认 30s）
   - 响应截断（默认 8000 字符）

---

## 3. 多智能体协作链路

### 数据流向

```
HTTP Request -> AiAgentController.multiAgentExecute()
                        |
                  MultiAgentService.execute(task, context)
                        |
                  +---------------------------------------------+
                  | Phase 1: Plan（规划）                         |
                  | LLM 分解任务为 <=5 个子任务                   |
                  | 正则解析 "SUBTASK N:" 格式                    |
                  +---------------------------------------------+
                        |
                  +---------------------------------------------+
                  | Phase 2: Execute（并行执行）                  |
                  | CompletableFuture.supplyAsync()              |
                  | 每个子任务独立 ReAct 循环（<=5 步）           |
                  | 工具：query_database, call_external_api       |
                  +---------------------------------------------+
                        |
                  +---------------------------------------------+
                  | Phase 3: Synthesize（综合）                   |
                  | LLM 合并子任务结果为最终答案                  |
                  | 失败时 fallback 拼接                          |
                  +---------------------------------------------+
                        |
                  HTTP Response
```

### 关键方法

#### 3.1 多智能体服务

**类**: `com.aiagent.agent.application.MultiAgentService`

```java
public String execute(String task, String context)
```

**核心参数**:
- `WORKER_TIMEOUT = 2min`（Worker 超时）
- `MAX_SUBTASKS = 5`（最大子任务数）

**执行流程**:

1. **plan(task)**:
   - LLM 使用 `PLANNER_PROMPT` 分解任务
   - 正则解析 `SUBTASK \d+:` 格式提取子任务

2. **executeInParallel(task, subtasks)**:
   - 每个子任务创建 `CompletableFuture.supplyAsync()`
   - Worker 执行 ReAct 循环（最多 5 步）
   - 工具：`query_database`, `call_external_api`
   - 超时控制（`WORKER_TIMEOUT`）

3. **synthesize(task, subtasks, results)**:
   - LLM 使用 `SYNTHESIZER_PROMPT` 合并结果
   - 失败时 fallback：直接拼接子任务结果

4. **fallbackToSingleAgent(task, context)**:
   - 整体失败时，直接调用 LLM 作为降级

**数据结构**:
```java
record WorkerResult(int index, String subtask, String result)
```

---

## 4. 文档入库链路

### 数据流向

```
HTTP Request (MultipartFile) -> AiAgentController.uploadDocument()
                                        |
                                  DocumentService.uploadDocument()
                                        |
                                  DocumentRepository.save() -> 状态: PENDING
                                  PlatformMetricsService.recordDocumentQueued()
                                        |
                                  DocumentIngestionService.ingestAsync()  <- @Async("taskExecutor")
                                        |
                                  markProcessingStarted() -> 状态: PROCESSING
                                        |
                                  DocumentParserFactory.parse(fileName, inputStream)
                                        |
                                  +---------------------------------------------+
                                  | 解析器选择（按文件扩展名）                   |
                                  | .pdf  -> PdfDocumentParser (PDFBox)          |
                                  | .docx -> WordDocumentParser (POI)            |
                                  | .md   -> MarkdownDocumentParser              |
                                  | .txt  -> TxtDocumentParser                   |
                                  +---------------------------------------------+
                                        |
                                  TextSplitter.split(content, chunkSize, chunkOverlap)
                                        |
                                  EmbeddingModel.embedAll(segments)
                                        |
                                  VectorStoreService.addAll(embeddings, segments)
                                        |
                                  DocumentChunkRepository.saveAll()
                                        |
                                  markProcessingCompleted() -> 状态: COMPLETED
                                  PlatformMetricsService.recordDocumentIngestion()
```

### 关键方法

#### 4.1 文档服务

**类**: `com.aiagent.knowledge.application.DocumentService`

```java
public Document uploadDocument(MultipartFile file)
public Map<String, Object> getDocumentStatus(Long documentId)
public List<RetrievalChunk> searchSimilar(String query, int topK, double threshold)
```

**核心逻辑**:
- `uploadDocument()`: 保存文档元数据 -> 调用异步入库 -> 返回 202
- `getDocumentStatus()`: 查询文档处理状态和元数据
- `searchSimilar()`: 向量相似度搜索

#### 4.2 异步入库服务

**类**: `com.aiagent.knowledge.application.DocumentIngestionService`

```java
@Async("taskExecutor")
public void ingestAsync(Long documentId, String fileName, byte[] fileBytes)
```

**核心逻辑**:
1. 标记处理开始（状态 -> PROCESSING）
2. 使用 `DocumentParserFactory` 选择解析器
3. 使用 `TextSplitter` 分块（基于 `DocumentSplitters.recursive()` + `OpenAiTokenizer`）
4. 批量 embedding（`EmbeddingModel.embedAll()`）
5. 批量写入向量存储（`VectorStoreService.addAll()`）
6. 保存 chunk 元数据到数据库
7. 标记处理完成/失败

#### 4.3 文本分块器

**类**: `com.aiagent.knowledge.infrastructure.splitter.TextSplitter`

```java
public List<TextSegment> split(String text, int chunkSize, int chunkOverlap)
```

- 使用 LangChain4j 的 `DocumentSplitters.recursive()`
- 基于 `OpenAiTokenizer` 进行 token 级分块
- 默认配置：`chunkSize=500`, `chunkOverlap=50`

#### 4.4 文档解析器工厂

**类**: `com.aiagent.knowledge.infrastructure.parser.DocumentParserFactory`

支持的格式（由 `ai.document.supported-formats` 配置）：
- `pdf` -> `PdfDocumentParser`（Apache PDFBox）
- `docx` -> `WordDocumentParser`（Apache POI）
- `md` -> `MarkdownDocumentParser`
- `doc` -> `WordDocumentParser`（Apache POI）
- `txt` -> `TxtDocumentParser`

**TxtDocumentParser** 支持智能格式检测：
- FAQ 格式（Q:/A: 标记）
- 对话格式（User:/Assistant: 标记）
- 文章格式（# 标题 + === 分隔）
- CSV 格式
- 混合格式（自动识别每个块）

---

## 5. RAG 检索链路

### 数据流向

```
AiAgentService.buildContextFromMultiRecall()
                        |
                  MultiRecallService.search(query, topK)
                        |
                  RagCacheService.getCachedResults()  <- 1小时 TTL
                        | (未命中)
                  +---------------------------------------------+
                  | 向量检索（Vector Search）                     |
                  | DocumentService.searchSimilar()              |
                  | -> VectorStoreService.search()               |
                  | -> Milvus / InMemory                         |
                  +---------------------------------------------+
                        |
                  +---------------------------------------------+
                  | BM25 关键词检索                               |
                  | Bm25Search.search()                          |
                  | 候选池：向量检索结果（BM25_CANDIDATE_POOL=50）|
                  +---------------------------------------------+
                        |
                  +---------------------------------------------+
                  | RRF 融合（Reciprocal Rank Fusion）            |
                  | rrfFuse(vectorResults, bm25Results)          |
                  | score = sum(1/(RRF_K + rank + 1))            |
                  | RRF_K = 60                                   |
                  +---------------------------------------------+
                        |
                  RagCacheService.cacheResults()
                  PlatformMetricsService.recordRagSearch()
                        |
                  返回 topK 个 RetrievalChunk
```

### 关键方法

#### 5.1 多路召回服务

**类**: `com.aiagent.rag.application.MultiRecallService`

```java
public List<RetrievalChunk> search(String query, int topK)
```

**核心参数**:
- `RRF_K = 60`（RRF 融合常数）
- `PER_ROUTE_TOP_K = 20`（每路返回条数）
- `BM25_CANDIDATE_POOL = 50`（BM25 候选池大小）

**执行流程**:
1. 检查 `RagCacheService` 缓存（TTL 1小时）
2. 向量检索（`PER_ROUTE_TOP_K` 条）
3. BM25 检索（基于向量检索结果作为候选池）
4. RRF 融合两路结果
5. 缓存结果并记录指标

**RRF 融合算法**:
```
score(doc) = sum(1 / (RRF_K + rank_i + 1))
```
- 对每路检索结果，按排名计算倒数分数
- 同一文档的分数累加
- 按总分降序排列，取 topK

#### 5.2 向量存储

**接口**: `com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService`

```java
void add(String id, Embedding embedding)
List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments)
List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double minScore)
void remove(String id)
void removeAll()
```

**实现类**:
- `MilvusVectorStoreService`（`@Primary`，Milvus 不可用时自动降级到内存存储）
- `InMemoryVectorStoreService`（测试/开发用）

**Milvus Collection 结构**（由 `MilvusInitConfig.CollectionsInitializer` 创建）:
- `id`（Int64，主键，自增）
- `question`（VarChar 1024）
- `answer`（VarChar 2048）
- `qa_text`（VarChar 3072）
- `qa_pair_id`（Int64）
- `category`（VarChar 100）
- `embedding`（FloatVector，维度由配置决定，默认 1024）
- `ts`（Int64）
- 索引：HNSW（COSINE，M=16，efConstruction=200）

#### 5.3 BM25 算法

**类**: `com.aiagent.rag.application.Bm25Search`

```java
public Bm25Search(List<RetrievalChunk> documents)
public List<RetrievalChunk> search(String query, int topK)
```

**核心参数**:
- `K1 = 1.2`（词频饱和度）
- `B = 0.75`（文档长度归一化）

**公式**:
```
score = IDF * (TF * (k1 + 1)) / (TF + k1 * (1 - b + b * docLen / avgDocLen))
IDF = log(1 + (N - n + 0.5) / (n + 0.5))
```

**分词**: 按非中文/非字母字符分割，中文按字切分，英文按词保留

#### 5.4 RAG 评估服务

**类**: `com.aiagent.rag.application.RagEvaluationService`

```java
public EvaluationReport evaluate(Map<String, List<String>> testDataset, List<Integer> topKs)
public EvaluationReport quickEvaluate(List<Integer> topKs)
```

**评估指标**:
- Recall@k：TopK 结果中包含相关文档的比例
- Precision@k：TopK 结果中相关文档的比例
- F1 分数：召回率和准确率的调和平均
- Latency：平均响应时间、P50、P99

**评估报告结构**:
```java
EvaluationReport {
    int datasetSize;
    List<Integer> topKs;
    Map<String, Object> configSnapshot;  // 当前配置快照
    Map<String, Map<String, Object>> metrics;  // k -> 指标名 -> 值
}
```

---

## 6. 流式聊天链路

### 数据流向

```
HTTP Request (SSE) -> AiAgentController.streamChat()
                              |
                    AiAgentService.streamChat()
                              |
                    SemanticCacheService.getIfCached()
                              | (命中) -> sink.tryEmitNext(cached) -> sink.tryEmitComplete()
                              | (未命中)
                    MultiRecallService.search()
                    LongContextManager.getOptimizedContext()
                              |
                    AiServices.builder(Assistant.class)
                        .streamingChatLanguageModel(streamingChatLanguageModel)
                        .build()
                        .chat(fullPrompt)
                              |
                    TokenStream
                        .onNext(token -> {
                            sink.tryEmitNext(token);
                            responseBuilder.append(token);
                        })
                        .onComplete(response -> {
                            semanticCacheService.put(question, responseBuilder.toString());
                            longContextManager.saveMessageAndMaybeSummarize(...);
                            sink.tryEmitComplete();
                        })
                        .onError(error -> sink.tryEmitError(error))
                        .start()
                              |
                    Flux<String> (5-min timeout) -> SSE Response
```

### 关键方法

#### 6.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamChat(
    @RequestParam String sessionId,
    @RequestParam String question,
    @RequestParam(defaultValue = "true") boolean useRag)
```

#### 6.2 服务层

**类**: `com.aiagent.agent.application.AiAgentService`

```java
public Flux<String> streamChat(String sessionId, String question, boolean useRag)
```

**核心逻辑**:

1. **缓存检查** - 命中则直接推送并结束
2. **构建 Prompt**（同普通聊天）
3. **流式调用** - 使用 `AiServices.builder(Assistant.class)` 构建流式调用
4. **Token 推送** - `onNext` 逐 token 推送，`onComplete` 缓存和保存历史
5. **超时控制** - 5分钟超时，错误降级

#### 6.3 SSE 连接管理

**类**: `com.aiagent.chat.infrastructure.SseService`

```java
public SseEmitter createEmitter(String sessionId)
public boolean send(String sessionId, String data)
public void sendEvent(String sessionId, String eventName, Object data)
public void complete(String sessionId)
public void completeWithError(String sessionId, Throwable error)
```

**核心参数**:
- `HEARTBEAT_INTERVAL_SECONDS = 15`（心跳间隔）
- `SSE_TIMEOUT_MS = 300_000`（5分钟超时）

**核心逻辑**:
- 使用 `ConcurrentHashMap<String, SseEmitter>` 管理活跃连接
- 单线程 `ScheduledExecutorService` 定期发送心跳（SSE comment `: heartbeat`）
- `@PreDestroy` 优雅关闭所有连接

---

## 7. 认证与鉴权链路

### 数据流向

```
HTTP Request -> SecurityFilterChain
                    |
              +-----+-----+
              | 公开端点？  |
              | /health    |
              | /auth/**   |
              | /, /admin  |
              | /admin.html|
              | /error     |
              | OPTIONS /**|
              | POST /session, /chat, /react/chat, /document/search|
              | GET /chat/stream, DELETE /session/{id}             |
              | /actuator/health, /actuator/info                   |
              +-----+-----+
                    | (否)
              AdminApiKeyFilter (before AnonymousAuthenticationFilter)
                    |
              +-----+------+
              | X-Admin-Api-Key 匹配？|
              +-----+------+
                    | (否)
              JwtAuthenticationFilter (before UsernamePasswordAuthenticationFilter)
                    |
              +-----+------+
              | Authorization: Bearer <token> |
              | JwtTokenProvider.validateToken()|
              +-----+------+
                    |
              SecurityContext 设置认证信息
                    |
              端点权限检查
```

### 关键组件

#### 7.1 安全配置

**类**: `com.aiagent.infrastructure.config.SecurityConfig`

- CSRF 禁用、HTTP Basic 禁用、表单登录禁用
- 无状态 Session（`STATELESS`）
- 自定义 401 JSON 响应

**公开端点**:
- `OPTIONS /**`
- `/health`, `/auth/**`, `/`, `/admin`, `/admin.html`, `/error`
- `POST /api/v1/agent/session`, `/chat`, `/react/chat`, `/document/search`
- `GET /api/v1/agent/chat/stream`
- `DELETE /api/v1/agent/session/{sessionId}`
- `/actuator/health`, `/actuator/info`, `/actuator/metrics`, `/actuator/metrics/**`, `/actuator/prometheus`

**需认证端点**:
- `GET /api/v1/agent/document/**`
- `POST /api/v1/agent/multi-agent/**`, `/document/upload`, `/cache`, `/evaluate`
- `/api/v1/ecommerce/**`, `/api/v1/cs/**`

#### 7.2 JWT 认证

**类**: `com.aiagent.infrastructure.security.JwtTokenProvider`

```java
public String generateToken(String username)
public String getUsernameFromToken(String token)
public boolean validateToken(String token)
```

- 使用 HMAC-SHA 算法签名
- Token 包含：subject（用户名）、issuedAt、expiration
- 过期时间由 `ai.security.jwt.expiration` 配置（默认 86400000ms = 24h）

**类**: `com.aiagent.infrastructure.security.JwtAuthenticationFilter`

- 从 `Authorization: Bearer <token>` 提取 token
- 验证 token 有效性
- 设置 `UsernamePasswordAuthenticationToken`（`ROLE_USER`）到 SecurityContext

#### 7.3 Admin API Key

**类**: `com.aiagent.infrastructure.config.AdminApiKeyFilter`

- 读取 `X-Admin-Api-Key` 请求头（由 `ai.security.admin-header-name` 配置）
- 与 `ai.security.admin-api-key` 配置值比对
- 匹配则设置 `ROLE_ADMIN` 权限

#### 7.4 认证控制器

**类**: `com.aiagent.auth.api.AuthController`

```java
@PostMapping("/login")    // 登录，返回 JWT token
@PostMapping("/register") // 注册新用户
```

- 密码使用 `BCryptPasswordEncoder` 加密存储
- 登录返回 `{token, username, type:"Bearer"}`

---

## 8. 缓存架构

### 缓存层级

```
+---------------------------------------------------------+
|                    应用层缓存                             |
+------------------+------------------+-------------------+
| SemanticCache    | RagCache         | EmbeddingCache    |
| TTL: 24h         | TTL: 1h          | TTL: 24h          |
| Redis            | Redis            | Redis             |
| 语义相似度匹配    | 查询结果缓存      | embedding 向量缓存 |
+------------------+------------------+-------------------+
```

### 关键组件

#### 8.1 Embedding 缓存

**类**: `com.aiagent.infrastructure.cache.EmbeddingCacheService`

```java
public float[] getCachedEmbedding(String text)
public void cacheEmbedding(String text, float[] vector)
```

- Redis Key 前缀：`ai:embedding:`
- TTL：24小时
- 所有 Redis 操作通过 `CacheExceptionHandler` 安全包装

#### 8.2 RAG 缓存

**类**: `com.aiagent.infrastructure.cache.RagCacheService`

```java
public List<RetrievalChunk> getCachedResults(String query)
public void cacheResults(String query, List<RetrievalChunk> results)
```

- Redis Key 前缀：`ai:rag-cache:`
- TTL：1小时
- 空结果不缓存

#### 8.3 缓存 Key 工具

**类**: `com.aiagent.infrastructure.cache.CacheKeyUtil`

```java
public static String buildKey(String prefix, String text)
```

- 使用 MD5 哈希生成 key：`prefix + md5(text)`

#### 8.4 缓存异常处理

**类**: `com.aiagent.infrastructure.cache.CacheExceptionHandler`

```java
public static <T> T safeRead(String operation, Supplier<T> supplier)
public static void safeWrite(String operation, Runnable runnable)
public static <T> T safeExecute(String operation, Supplier<T> supplier, T defaultValue)
```

- 所有缓存操作异常安全（catch -> log warning -> 返回 null/defaultValue）

#### 8.5 Spring Cache 配置

**类**: `com.aiagent.infrastructure.config.PerformanceConfig`

- 基于 Redis 的 `RedisCacheManager`
- 默认 TTL 由 `ai.performance.cache.ttl` 配置（默认 3600s）
- 序列化：String key + JSON value
- 按缓存名独立 TTL：

| 缓存名 | TTL |
|--------|-----|
| session | 24h |
| semantic | 1h |
| rag | 30min |
| embedding | 24h |
| config | 7 days |

---

## 9. 弹性容错链路

### 数据流向

```
ChatLanguageModel.generate()
        |
  ResilientChatLanguageModel
        |
  Retry.executeSupplier()  <- maxAttempts=3, waitDuration=2s
        |
  CircuitBreaker.executeSupplier()  <- failureRateThreshold=50%
        |                              slidingWindowSize=10
        |                              waitDurationInOpenState=30s
        |
  FallbackChatLanguageModel
        |
  +-----+------+
  | 主模型调用   | -> DeepSeek / Qianwen / Doubao / Qwen3 Flash
  | 异常？       |
  +-----+------+
        | (RateLimitDetector.isRateLimit() == true)
  +-----+------+
  | 降级到本地   | -> Ollama (local)
  | [MODEL-DEGRADE]|
  +-----+------+
        | (其他异常)
  +-----+------+
  | [MODEL-FAILED]| -> 重新抛出异常
  +-----+------+
```

### 关键组件

#### 9.1 弹性模型包装

**类**: `com.aiagent.infrastructure.config.ResilientChatLanguageModel`

```java
public GenerateResponse generate(List<Message> messages)
```

- 调用链：`retry -> circuitBreaker -> delegate`
- 熔断器打开 -> 抛出 `LlmServiceUnavailableException("circuit breaker open")`
- 其他异常 -> 抛出 `LlmServiceUnavailableException`

#### 9.2 Fallback 模型

**类**: `com.aiagent.infrastructure.config.FallbackChatLanguageModel`

```java
public GenerateResponse generate(List<Message> messages)
```

- 主模型异常 -> 检查 `RateLimitDetector.isRateLimit()`
- 限流 -> `[MODEL-DEGRADE]` 降级到本地 Ollama
- 其他错误 -> `[MODEL-FAILED]` 重新抛出

**类**: `com.aiagent.infrastructure.config.FallbackStreamChatLanguageModel`

- 流式版本的 Fallback 实现
- `onError` 回调中检测限流 -> 降级

#### 9.3 限流检测

**类**: `com.aiagent.infrastructure.config.RateLimitDetector`

```java
public static boolean isRateLimit(Throwable e)
```

- 关键词检测：`429`, `rate limit`, `too many requests`, `throttling`, `rate_limit_exceeded`
- 支持中文关键词

#### 9.4 熔断器与重试配置

**类**: `com.aiagent.infrastructure.config.ResilienceConfig`

**熔断器**:
- `failureRateThreshold = 50%`
- `waitDurationInOpenState = 30s`
- `slidingWindowSize = 10`
- `minimumNumberOfCalls = 5`
- `permittedCallsInHalfOpen = 3`
- 记录异常：`IOException`, `TimeoutException`, `RuntimeException`

**重试**:
- `maxAttempts = 3`
- `waitDuration = 2s`
- 重试异常：`IOException`, `TimeoutException`

#### 9.5 模型配置

**类**: `com.aiagent.infrastructure.config.AiModelConfig`

```java
@Bean public ChatLanguageModel chatLanguageModel()
@Bean public StreamingChatLanguageModel streamingChatLanguageModel()
@Bean public EmbeddingModel embeddingModel()
@Bean public Tokenizer tokenizer()
```

**模型 Provider 切换**（`ai.model.provider`）:
- `deepseek` -> DeepSeek
- `qianwen` -> 通义千问
- `doubao` -> 豆包
- `qwen3-flash` -> Qwen3 Flash
- `local` -> 本地 Ollama

**Embedding Provider 切换**（`ai.embedding.provider`）:
- `deepseek` -> DeepSeek Embedding
- `qianwen` -> 通义千问 Embedding
- `local` -> 本地 bge-m3
- `local-qwen3` -> 本地 qwen3-embedding:4b
- `siliconflow` -> SiliconFlow BAAI/bge-m3

---

## 10. 可观测性与指标链路

### 指标体系

**类**: `com.aiagent.infrastructure.metrics.PlatformMetricsService`

```java
public Timer.Sample startSample()
public void recordChat(String mode, boolean useRag, boolean cacheHit, boolean success, Timer.Sample sample)
public void recordRagSearch(boolean cacheHit, int resultCount, Timer.Sample sample)
public void recordDocumentQueued()
public void recordDocumentIngestion(String status, int chunkCount, Timer.Sample sample)
public void recordCacheOperation(String cacheName, String operation, boolean hit)
public void recordDatabaseQuery(String table, String operation, long durationMs)
```

### 指标列表

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `ai.chat.requests.total` | Counter | mode, rag, cache, status | 聊天请求总数 |
| `ai.chat.latency` | Timer | mode, rag, cache, status | 聊天延迟（p50/p95/p99，SLO: 100ms/500ms/2s） |
| `ai.rag.search.total` | Counter | - | RAG 搜索总数 |
| `ai.rag.search.latency` | Timer | - | RAG 搜索延迟 |
| `ai.rag.results.count` | DistributionSummary | - | RAG 搜索结果数量分布 |
| `ai.document.ingestion.queued.total` | Counter | - | 文档入队列总数 |
| `ai.document.ingestion.total` | Counter | status | 文档入库总数 |
| `ai.document.ingestion.latency` | Timer | status | 文档入库延迟 |
| `ai.document.chunk.count` | DistributionSummary | - | 文档分块数量分布 |
| `ai.cache.operations.total` | Counter | cache, operation | 缓存操作总数 |
| `ai.database.query.latency` | Timer | table, operation | 数据库查询延迟 |

### 暴露端点

- `/actuator/metrics` - Spring Boot Actuator 指标
- `/actuator/prometheus` - Prometheus 格式指标
- `/actuator/health` - 健康检查（含详细信息）
- `/actuator/info` - 应用信息

---

## 11. 电商知识库导入链路

### 数据流向

```
HTTP Request -> EcommerceImportController.importKnowledge()
                        |
                  EcommerceKnowledgeImportService.importFromFile(filePath)
                        |
                  +---------------------------------------------+
                  | 1. 读取文件（JSONL 格式 QA 对）              |
                  | 2. 解析为 QaRecord 列表                      |
                  | 3. 批量 Embedding（Ollama REST API）         |
                  | 4. 写入 Milvus（ecommerce_qa collection）    |
                  | 5. 保存到 MySQL（ecommerce_qa_pairs 表）     |
                  +---------------------------------------------+
                        |
                  返回 ImportResult
```

### 关键组件

**类**: `com.aiagent.ecommerce.application.EcommerceKnowledgeImportService`

- Milvus 客户端通过 `@Autowired(required = false)` 注入（CI 环境无 Milvus 时优雅降级）
- 导入前检查 Milvus 可用性
- 批量写入 Milvus `ecommerce_qa` collection
- 批量 embedding 通过 Ollama REST API（`/api/embed`）
- 批处理配置：`batchSize=18`, `batchIntervalMs=200`

**类**: `com.aiagent.ecommerce.application.EcommerceDataGeneratorService`

- 自动生成电商 QA 数据
- 支持 5 种格式：FAQ、对话、文章、CSV、JSONL
- 配置：`target-per-category=500`, `batch-size=20`
- 8 个电商类别：物流咨询、退换货、商品咨询、价格优惠、售后服务、支付问题、库存查询、活动促销
- 预算控制（默认 3.0 元）

**端点**:

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/ecommerce/import` | 异步导入 JSONL 文件 |
| POST | `/api/v1/ecommerce/import/test` | 预览导入数据 |
| POST | `/api/v1/ecommerce/generator/run` | 全格式数据生成 |
| POST | `/api/v1/ecommerce/generator/faq` | FAQ 格式生成 |
| POST | `/api/v1/ecommerce/generator/conversations` | 对话格式生成 |
| POST | `/api/v1/ecommerce/generator/articles` | 文章格式生成 |
| POST | `/api/v1/ecommerce/generator/csv` | CSV 格式生成 |
| POST | `/api/v1/ecommerce/generator/jsonl` | JSONL 格式生成 |

---

## 12. 客服数据导入链路

### 数据流向

```
HTTP Request -> CsDataImportController.importData()
                        |
                  CsDataImportService.importFromJsonl(filePath)
                        |
                  +---------------------------------------------+
                  | 1. 断点续传支持（.checkpoint 文件）          |
                  | 2. 逐行读取 JSONL（OpenAI chat 格式）        |
                  | 3. 解析 user/assistant 消息                  |
                  | 4. 批量 Embedding（LangChain4j）             |
                  | 5. 写入 Milvus（ecommerce_qa collection）    |
                  | 6. 保存到 MySQL（ecommerce_qa_pairs 表）     |
                  | 7. 定期 flush Milvus（每28批次）             |
                  +---------------------------------------------+
                        |
                  返回 ImportResult
```

### 关键组件

**类**: `com.aiagent.customer_support.application.CsDataImportService`

- Milvus 客户端通过 `@Autowired(required = false)` 注入
- **断点续传**：通过 `.checkpoint` 文件记录处理进度
- 数据目录由 `cs.import.data-dir` 配置
- 线程安全的进度跟踪（`AtomicLong`）
- 批处理配置：`batchSize=18`

**类**: `com.aiagent.customer_support.api.CsDataImportController`

**端点**:

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/cs/data/import` | 导入单个 JSONL 文件 |
| POST | `/api/cs/data/import-all` | 导入所有训练文件（train/dev/test） |
| GET | `/api/cs/data/progress` | 查询导入进度 |
| POST | `/api/cs/data/finalize` | 导入后 Milvus 操作（flush + build index + load） |
| GET | `/api/cs/data/files` | 列出可用数据文件 |

**Milvus 管理**（`MilvusAdminService`）:
- HNSW 索引构建（COSINE，M=16，efConstruction=200）
- 异步索引构建（最长 20 分钟轮询）
- Collection flush 和 load

---

## 附录：核心配置

### 模型配置

```yaml
ai:
  model:
    provider: deepseek  # deepseek | qianwen | doubao | qwen3-flash | local
    deepseek:
      api-key: 
      model-name: deepseek-chat-v2
      base-url: https://api.deepseek.com/v1
    qianwen:
      api-key: 
      model-name: qwen3.7-max
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    doubao:
      api-key: 
      model-name: doubao-seed-2-0-mini-260428
      base-url: https://ark.cn-beijing.volces.com/api/v3
    qwen3-flash:
      api-key: 
      model-name: qwen3.7-flash
    local:
      base-url: http://localhost:11434/v1
      model-name: coder:qwen7b
    fallback:
      enabled: true
      max-retries: 1
  embedding:
    provider: siliconflow  # deepseek | qianwen | local | local-qwen3 | siliconflow
    siliconflow:
      api-key: 
      model-name: BAAI/bge-m3
      base-url: https://api.siliconflow.cn/v1
      dimension: 1024
```

### RAG 配置

```yaml
ai:
  document:
    chunk-size: 500
    chunk-overlap: 50
    supported-formats: pdf,docx,doc,md,txt
  rag:
    top-k: 5
    similarity-threshold: 0.7
    enable-hybrid-search: true
  vector-store:
    type: milvus  # milvus | inmemory
    milvus:
      host: 
      port: 19530
      collection-name: ai_agent_documents
      dimension: 1024
      connection-timeout-ms: 2000
```

### 会话配置

```yaml
ai:
  session:
    ttl: 86400          # 24小时
    max-messages: 100
    sliding-window-size: 10
    summary-interval: 5
```

### 安全配置

```yaml
ai:
  security:
    admin-api-key: 
    admin-header-name: X-Admin-Api-Key
    jwt:
      secret: 
      expiration: 86400000  # 24小时
```

### 工具配置

```yaml
ai:
  tool:
    enabled: true
    database-query:
      enabled: true
      max-rows: 100
      allowed-tables: conversations,messages,documents,document_chunks,users,
                      ecommerce_qa_pairs,ecommerce_feedback,message_classify_log,
                      vision_analysis_cache
    api-call:
      enabled: true
      timeout: 30000
      allowed-methods: GET,POST
      allowed-hosts: 
      max-response-chars: 8000
```

### 性能配置

```yaml
ai:
  performance:
    async-thread-pool:
      core-size: 20
      max-size: 100
      queue-capacity: 200
    cache:
      enabled: true
      ttl: 3600
```

### 异步线程池

**类**: `com.aiagent.infrastructure.config.AsyncConfig`

| 线程池 | 核心线程 | 最大线程 | 队列容量 | 前缀 | 用途 |
|--------|---------|---------|---------|------|------|
| taskExecutor | 20 | 100 | 200 | ai-agent-async- | 通用异步任务 |
| ioTaskExecutor | 10 | 50 | 100 | ai-agent-io- | IO 密集型任务 |

### 数据库优化

**类**: `com.aiagent.infrastructure.config.DatabaseOptimizationConfig`

启动时自动创建索引（忽略重复错误）：
- `conversations(user_id)`, `conversations(updated_at DESC)`
- `messages(conversation_id)`, `messages(created_at DESC)`
- `documents(user_id)`, `documents(file_type)`, `documents(created_at DESC)`
- `document_chunks(document_id)`, `document_chunks(document_id, chunk_index)`
- `ecommerce_qa_pairs(category)`, `ecommerce_qa_pairs(created_at DESC)`

---

## 附录：数据库 Schema

### 核心表

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `users` | 用户表 | id, username, password, email, enabled |
| `conversations` | 会话表 | id, session_id, user_id, title, message_count |
| `messages` | 消息表 | id, session_id, role, content, model_name, latency_ms |
| `documents` | 文档表 | id, file_name, file_type, processing_status, chunk_count |
| `document_chunks` | 文档分块表 | id, document_id, chunk_index, content, vector_id |
| `ecommerce_qa_pairs` | 电商 QA 对 | id, question, answer, category, hit_count |
| `ecommerce_feedback` | 电商反馈 | id, session_id, question, answer, rating |
| `message_classify_log` | 消息分类日志 | id, session_id, classified_type, confidence |
| `vision_analysis_cache` | 视觉分析缓存 | id, image_hash, model_name, result_json |

### 迁移管理

- 使用 Flyway 管理数据库迁移
- 迁移脚本：`src/main/resources/db/migration/V1__initial_schema.sql`
- 配置：`baseline-on-migrate: true`, `validate-on-migrate: true`

---

**文档版本**: v2.0
**最后更新**: 2026-08-06
