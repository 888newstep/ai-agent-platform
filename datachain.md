# AI Agent Platform 数据链路文档

## 目录

- [1. 普通聊天链路](#1-普通聊天链路)
- [2. ReAct 推理链路](#2-react-推理链路)
- [3. 多智能体协作链路](#3-多智能体协作链路)
- [4. 文档入库链路](#4-文档入库链路)
- [5. RAG 检索链路](#5-rag-检索链路)
- [6. 流式聊天链路](#6-流式聊天链路)

---

## 1. 普通聊天链路

### 数据流向

```
HTTP Request -> AiAgentController.chat() -> AiAgentService.chat() -> SemanticCacheService.getIfCached()
                                                                    | (未命中)
                                                              MultiRecallService.search()
                                                                    |
                                                              LongContextManager.getOptimizedContext()
                                                                    |
                                                              ChatLanguageModel.generate()
                                                                    |
                                                              SemanticCacheService.put()
                                                              LongContextManager.saveMessageAndMaybeSummarize()
                                                                    |
                                                              HTTP Response
```

### 关键方法

#### 1.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@PostMapping("/chat")
public ResponseEntity<Map<String, Object>> chat(
    @RequestParam String sessionId,
    @RequestParam String question,
    @RequestParam(defaultValue = "false") boolean useRag)
```

- 接收 HTTP POST 请求
- 参数：sessionId（会话ID）、question（用户问题）、useRag（是否启用RAG）
- 调用 `AiAgentService.chat()`

#### 1.2 服务层

**类**: `com.aiagent.agent.application.AiAgentService`

```java
public String chat(String sessionId, String question, boolean useRag)
```

**执行步骤**:

1. **语义缓存检查**
   ```java
   String cached = semanticCacheService.getIfCached(question);
   ```
   - 调用 `SemanticCacheService.getIfCached()`
   - 问题 -> embedding -> 遍历 Redis 缓存索引 -> 余弦相似度 >= 0.92 -> 命中返回

2. **RAG 上下文构建**（useRag=true 时）
   ```java
   String context = useRag ? buildContextFromMultiRecall(question) : "";
   ```
   - 调用 `MultiRecallService.search(question, topK)`
   - 返回 topK 个相关文档片段

3. **会话上下文构建**
   ```java
   String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
   ```
   - 获取滑动窗口最近消息（默认10轮）
   - 检索相关历史摘要（每5轮生成一次）

4. **Prompt 构建**
   ```java
   String fullPrompt = buildPrompt(optimizedHistory, context, question);
   ```

5. **模型调用**
   ```java
   String response = chatLanguageModel.generate(fullPrompt);
   ```
   - 调用 `ChatLanguageModel.generate()`
   - 根据配置选择 DeepSeek/通义千问/豆包/本地模型
   - 带 Fallback 机制

6. **缓存回写**
   ```java
   semanticCacheService.put(question, response);
   longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
   longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
   ```
   - 写入语义缓存（TTL 24小时）
   - 追加到会话历史
   - 每5轮触发摘要生成

#### 1.3 语义缓存层

**类**: `com.aiagent.infrastructure.cache.SemanticCacheService`

```java
public String getIfCached(String question)
public void put(String question, String answer)
```

**核心逻辑**:

- **getIfCached()**:
  1. 问题 -> embedding（优先从 EmbeddingCache 获取）
  2. 遍历 Redis 中所有缓存条目
  3. 计算余弦相似度：`cosineSimilarity(queryEmbedding, cachedEmbedding)`
  4. 阈值 >= 0.92 -> 命中返回

- **put()**:
  1. 问题 -> embedding
  2. 生成缓存 Key：`semantic_cache:{hash}`
  3. 存入 Redis：`{embedding: [...], answer: "...", timestamp: ...}`
  4. TTL 24小时

#### 1.4 会话管理层

**类**: `com.aiagent.infrastructure.memory.LongContextManager`

```java
public String getOptimizedContext(String sessionId, String question)
public void saveMessageAndMaybeSummarize(String sessionId, String role, String content)
```

**核心逻辑**:

- **getOptimizedContext()**:
  1. 获取滑动窗口最近消息（Redis Key: `history:{sessionId}`）
  2. 检索相关历史摘要（Redis Key: `summary:{sessionId}`）
  3. 按关键词匹配评分，取 topK 条摘要
  4. 拼接返回

- **saveMessageAndMaybeSummarize()**:
  1. 追加消息到 Redis 列表
  2. 超出窗口大小（默认10轮）-> 丢弃最早消息
  3. 每5轮触发 `generateAndStoreSummary()`
  4. 摘要存入 Redis，保留最近20条

---

## 2. ReAct 推理链路

### 数据流向

```
HTTP Request -> AiAgentController.reactChat() -> AiAgentService.reactChat()
                                                      |
                                                ReActAgent.execute()
                                                      |
                                            +---------------------+
                                            |  Thought -> Action  |
                                            |  Action Input       |
                                            |  Observation        |
                                            |  (循环最多10步)      |
                                            +---------------------+
                                                      |
                                                ToolService.queryDatabase()
                                                ToolService.callExternalApi()
                                                      |
                                                Final Answer
                                                      |
                                                HTTP Response
```

### 关键方法

#### 2.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@PostMapping("/react/chat")
public ResponseEntity<Map<String, Object>> reactChat(
    @RequestParam String sessionId,
    @RequestParam String question,
    @RequestParam(defaultValue = "false") boolean useRag)
```

#### 2.2 服务层

**类**: `com.aiagent.agent.application.AiAgentService`

```java
public String reactChat(String sessionId, String question, boolean useRag)
```

**执行步骤**:

1. 语义缓存检查（同普通聊天）
2. RAG 上下文构建（同普通聊天）
3. 会话上下文构建（同普通聊天）
4. **ReAct 循环**
   ```java
   String response = reActAgent.execute(question, context, optimizedHistory);
   ```
5. 缓存回写（同普通聊天）

#### 2.3 ReAct 核心

**类**: `com.aiagent.agent.application.ReActAgent`

```java
public String execute(String question, String context, String history)
```

**核心逻辑**:

1. **构建初始 Prompt**
   ```java
   String toolsDescription = buildToolsDescription();
   String userPrompt = buildUserPrompt(question, context, history);
   ```
   - 工具描述：
     ```
     - query_database: 执行 SQL 查询数据库
     - call_external_api: 调用外部 HTTP API
     ```

2. **循环执行**（最多10步，超时3分钟）
   ```java
   for (int step = 0; step < MAX_STEPS; step++) {
       String llmOutput = chatLanguageModel.generate(prompt);
       // 提取 Action 和 Action Input
       // 调用工具
       // 获取 Observation
       // 死循环检测
   }
   ```

3. **工具分发**
   ```java
   private String executeTool(String actionName, String actionInput)
   ```
   - `query_database` -> `toolService.queryDatabase(sql)`
   - `call_external_api` -> `toolService.callExternalApi(url, method, body)`

4. **安全防护**
   - SQL：只允许 SELECT，表白名单，关键词黑名单，行数限制
   - API：host allowlist，method allowlist，私有地址拦截

5. **死循环检测**
   ```java
   if (isRepeating(observations)) {
       return "我尝试了多次仍然无法完成这个任务...";
   }
   ```
   - 相同 Observation 连续出现3次 -> 强制终止

#### 2.4 工具层

**类**: `com.aiagent.agent.infrastructure.tool.ToolService`

```java
@Tool("query_database")
public String queryDatabase(String sql)

@Tool("call_external_api")
public String callExternalApi(String url, String method, String body)
```

**queryDatabase() 核心逻辑**:

1. SQL 校验
   ```java
   String executableSql = buildExecutableSelect(sql);
   ```
   - 禁止 INSERT/UPDATE/DELETE
   - 禁止注释（`--`、`/*`）
   - 禁止多语句（`;`）
   - 表白名单校验
   - 自动添加 LIMIT

2. 执行查询
   ```java
   conn.setReadOnly(true);
   stmt.setMaxRows(maxRows);
   ```

**callExternalApi() 核心逻辑**:

1. URL 校验
   ```java
   URI uri = validateUri(url, normalizedMethod);
   ```
   - host allowlist
   - method allowlist
   - 私有地址拦截
   - 超时控制（默认30s）

2. HTTP 调用
   ```java
   HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
   ```

---

## 3. 多智能体协作链路

### 数据流向

```
HTTP Request -> AiAgentController.multiAgentExecute()
                        |
              MultiAgentService.execute(task, context)
                        |
              +---------------------+
              |  Supervisor 规划    |
              |  拆分子任务          |
              +---------------------+
                        |
              +---------------------+
              |  Worker 并行执行    |
              |  (ForkJoinPool)     |
              +---------------------+
                        |
              +---------------------+
              |  Supervisor 汇总    |
              |  合成最终答案        |
              +---------------------+
                        |
              HTTP Response
```

### 关键方法

#### 3.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@PostMapping("/multi-agent/execute")
public ResponseEntity<Map<String, Object>> multiAgentExecute(
    @RequestParam String task,
    @RequestParam(required = false) String context)
```

#### 3.2 服务层

**类**: `com.aiagent.agent.application.MultiAgentService`

```java
public String execute(String task, String context)
```

**核心逻辑**:

1. **规划阶段**
   ```java
   List<String> subtasks = plan(task);
   ```
   - Supervisor 用 LLM 拆分任务
   - 正则提取：`SUBTASK\s+(\d+):\s*(.+)`
   - 最多拆 MAX_SUBTASKS 个子任务
   - 失败则降级为单 Agent

2. **执行阶段**
   ```java
   List<String> results = executeInParallel(task, subtasks);
   ```
   - 使用 ForkJoinPool 并行执行
   - 每个 Worker 独立超时控制
   - Worker 判断：
     - 包含"查询/搜索/计算"等关键词 -> ReAct 模式
     - 否则 -> 直接 LLM

3. **汇总阶段**
   ```java
   String finalAnswer = synthesize(task, subtasks, results);
   ```
   - Supervisor 收集所有结果
   - 用 LLM 合成最终答案

#### 3.3 Worker 执行

```java
private String workerExecute(String subtask)
```

**逻辑**:

```java
boolean needsTools = subtask.matches(".*(查询|搜索|计算|统计|查找|获取|数据库|API).*");

if (needsTools) {
    return executeWorkerReAct(subtask);  // ReAct 模式
} else {
    return chatLanguageModel.generate(prompt);  // 直接 LLM
}
```

---

## 4. 文档入库链路

### 数据流向

```
HTTP Request (MultipartFile) -> AiAgentController.uploadDocument()
                                      |
                            DocumentService.uploadDocument()
                                      |
                            MySQL: INSERT documents (status=PENDING)
                                      |
                            DocumentIngestionService.ingestAsync() [异步]
                                      |
                            +---------------------+
                            |  1. 解析文件         |
                            |  DocumentParser     |
                            +---------------------+
                                      |
                            +---------------------+
                            |  2. 切分 Chunk      |
                            |  TextSplitter       |
                            +---------------------+
                                      |
                            +---------------------+
                            |  3. Embedding       |
                            |  EmbeddingModel     |
                            +---------------------+
                                      |
                            +---------------------+
                            |  4. 写入向量库       |
                            |  VectorStoreService |
                            +---------------------+
                                      |
                            +---------------------+
                            |  5. 保存元数据       |
                            |  DocumentChunk      |
                            +---------------------+
                                      |
                            MySQL: UPDATE documents (status=COMPLETED)
```

### 关键方法

#### 4.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@PostMapping("/document/upload")
public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file)
```

#### 4.2 服务层

**类**: `com.aiagent.knowledge.application.DocumentService`

```java
public Document uploadDocument(MultipartFile file)
```

**执行步骤**:

1. **创建文档记录**
   ```java
   Document document = Document.builder()
       .fileName(fileName)
       .fileType(extractFileType(fileName))
       .fileSize(file.getSize())
       .chunkCount(0)
       .processingStatus(DocumentProcessingStatus.PENDING)
       .build();
   document = documentRepository.save(document);
   ```

2. **异步处理**
   ```java
   documentIngestionService.ingestAsync(document.getId(), fileName, fileBytes);
   ```
   - 标记 `@Async("taskExecutor")`
   - 在独立线程池执行

#### 4.3 异步入库

**类**: `com.aiagent.knowledge.application.DocumentIngestionService`

```java
@Async("taskExecutor")
public void ingestAsync(Long documentId, String fileName, byte[] fileBytes)
```

**核心逻辑**:

1. **解析文件**
   ```java
   String content = parserFactory.parse(fileName, inputStream);
   ```
   - 策略模式，按文件后缀选择解析器
   - 支持：PDF、Word、Markdown、TXT

2. **切分 Chunk**
   ```java
   List<TextSegment> segments = textSplitter.split(content, chunkSize, chunkOverlap);
   ```
   - 默认 chunkSize=500，chunkOverlap=50
   - 使用 LangChain4j 的 `DocumentSplitters.recursive()`

3. **批量 Embedding**
   ```java
   List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
   ```
   - 当前配置：SiliconFlow BAAI/bge-m3，1024维

4. **写入向量库**
   ```java
   List<String> vectorIds = vectorStoreService.addAll(embeddings, segments);
   ```
   - 写入 Milvus
   - 返回 vectorId 列表

5. **保存元数据**
   ```java
   documentChunkRepository.saveAll(chunks);
   ```
   - 每个 chunk 记录：documentId、chunkIndex、content、charCount、vectorId

6. **更新状态**
   ```java
   markProcessingCompleted(documentId, chunkCount);
   ```
   - status -> COMPLETED
   - 记录完成时间

---

## 5. RAG 检索链路

### 数据流向

```
AiAgentService.buildContextFromMultiRecall()
                        |
              MultiRecallService.search(query, topK)
                        |
              +---------------------+
              |  1. RAG 缓存检查    |
              |  RagCacheService    |
              +---------------------+
                        | (未命中)
              +---------------------+
              |  2. 向量召回        |
              |  vectorSearch()     |
              |  (topK=50)          |
              +---------------------+
                        |
              +---------------------+
              |  3. BM25 重排       |
              |  bm25Search()       |
              |  (topK=20)          |
              +---------------------+
                        |
              +---------------------+
              |  4. RRF 融合        |
              |  rrfFuse()          |
              |  (topK=最终结果)     |
              +---------------------+
                        |
              RagCacheService.cacheResults()
                        |
              返回 List<RetrievalChunk>
```

### 关键方法

#### 5.1 多路召回

**类**: `com.aiagent.rag.application.MultiRecallService`

```java
public List<RetrievalChunk> search(String query, int topK)
```

**核心逻辑**:

1. **RAG 缓存检查**
   ```java
   List<RetrievalChunk> cached = ragCacheService.getCachedResults(query);
   ```
   - 精确匹配查询字符串
   - 命中则直接返回

2. **向量召回**
   ```java
   List<RetrievalChunk> vectorCandidates = vectorSearch(query, BM25_CANDIDATE_POOL);
   ```
   - 调用 `DocumentService.searchSimilar()`
   - 问题 -> embedding -> Milvus 检索
   - 召回 50 个候选

3. **BM25 重排**
   ```java
   List<RetrievalChunk> bm25Results = bm25SearchOnCandidates(query, vectorCandidates, PER_ROUTE_TOP_K);
   ```
   - 对 50 个向量候选做 BM25 重排
   - 取前 20 个
   - **注意**：BM25 不是独立全文检索，而是对向量候选做二次排序

4. **RRF 融合**
   ```java
   List<RetrievalChunk> fusedResults = rrfFuse(List.of(vectorResults, bm25Results), topK);
   ```
   - 公式：`score(d) = Sum 1/(k + rank_i(d))`
   - k=60（经典值）
   - 合并两路结果，按 RRF 分数排序

5. **缓存结果**
   ```java
   ragCacheService.cacheResults(query, fusedResults);
   ```

#### 5.2 向量检索

**类**: `com.aiagent.knowledge.application.DocumentService`

```java
public List<RetrievalChunk> searchSimilar(String query, int topK, double threshold)
```

**核心逻辑**:

```java
var queryEmbedding = embeddingModel.embed(query).content();
List<EmbeddingMatch<TextSegment>> matches = vectorStoreService.search(queryEmbedding, topK, threshold);
```

- 问题 -> embedding
- 调用 `VectorStoreService.search()`
- 过滤：相似度 >= threshold（默认 0.7）
- 返回 topK 个匹配

#### 5.3 向量存储

**类**: `com.aiagent.knowledge.infrastructure.vectorstore.MilvusVectorStoreService`

```java
public List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int topK, double threshold)
```

**核心逻辑**:

- 调用 Milvus SDK 的 `search()` API
- Collection 结构：
  - `id`（主键）
  - `embedding`（向量，1024维）
  - `text`（原文）
  - `metadata`（JSON）
- 相似度：余弦距离

#### 5.4 BM25 算法

**类**: `com.aiagent.rag.application.Bm25Search`

```java
public List<RetrievalChunk> search(String query, int topK)
```

**核心逻辑**:

- 公式：`score = IDF * (TF * (k1 + 1)) / (TF + k1 * (1 - b + b * docLen / avgDocLen))`
- 参数：k1=1.2，b=0.75
- IDF：`log((N - n + 0.5) / (n + 0.5) + 1)`
- 分词：简单按空格和标点切分（无中文分词）

---

## 6. 流式聊天链路

### 数据流向

```
HTTP Request (SSE) -> AiAgentController.streamChat()
                              |
                    AiAgentService.streamChat()
                              |
                    SemanticCacheService.getIfCached()
                              | (未命中)
                    MultiRecallService.search()
                    LongContextManager.getOptimizedContext()
                              |
                    AiServices.builder()
                        .streamingChatLanguageModel(...)
                        .build()
                        .chat(fullPrompt)
                              |
                    TokenStream
                        .onNext(token -> sink.tryEmitNext(token))
                        .onComplete(response -> ...)
                        .onError(error -> ...)
                        .start()
                              |
                    Flux<String> -> SSE Response
```

### 关键方法

#### 6.1 入口层

**类**: `com.aiagent.agent.api.AiAgentController`

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamChat(
    @RequestParam String sessionId,
    @RequestParam String question,
    @RequestParam(defaultValue = "false") boolean useRag)
```

#### 6.2 服务层

**类**: `com.aiagent.agent.application.AiAgentService`

```java
public Flux<String> streamChat(String sessionId, String question, boolean useRag)
```

**核心逻辑**:

1. **缓存检查**
   ```java
   String cached = semanticCacheService.getIfCached(question);
   if (cached != null) {
       sink.tryEmitNext(cached);
       sink.tryEmitComplete();
       return sink.asFlux();
   }
   ```

2. **构建 Prompt**（同普通聊天）

3. **流式调用**
   ```java
   TokenStream tokenStream = AiServices.builder(Assistant.class)
       .streamingChatLanguageModel(streamingChatLanguageModel)
       .build()
       .chat(fullPrompt);
   ```

4. **Token 推送**
   ```java
   tokenStream
       .onNext(token -> {
           sink.tryEmitNext(token);
           responseBuilder.append(token);
       })
       .onComplete(response -> {
           semanticCacheService.put(question, responseBuilder.toString());
           longContextManager.saveMessageAndMaybeSummarize(...);
           sink.tryEmitComplete();
       })
       .onError(error -> {
           sink.tryEmitError(error);
       })
       .start();
   ```

5. **超时控制**
   ```java
   return sink.asFlux()
       .timeout(Duration.ofMinutes(5))
       .onErrorResume(e -> Flux.just("[Error: " + e.getMessage() + "]"));
   ```

---

## 附录：核心配置

### 模型配置

**类**: `com.aiagent.infrastructure.config.AiModelConfig`

```java
@Bean
public ChatLanguageModel chatLanguageModel()
```

- Provider 切换：`ai.model.provider`
  - deepseek
  - qianwen
  - doubao
  - local
- Fallback：主模型失败 -> 自动切本地 Ollama
- Resilience4j：熔断器 + 重试

### RAG 配置

**类**: `com.aiagent.infrastructure.config.AiProperties`

```yaml
ai:
  document:
    chunk-size: 500
    chunk-overlap: 50
  rag:
    top-k: 5
    similarity-threshold: 0.7
    enable-hybrid-search: true
```

### 会话配置

```yaml
ai:
  session:
    ttl: 86400  # 24小时
    max-messages: 100
    sliding-window-size: 10
    summary-interval: 5
```

---

## 附录：关键指标

**类**: `com.aiagent.infrastructure.metrics.PlatformMetricsService`

- `ai.chat.latency`：聊天延迟
- `ai.rag.search.latency`：RAG 检索延迟
- `ai.document.ingestion.latency`：文档入库延迟
- 缓存命中率
- 工具调用次数

暴露端点：`/actuator/prometheus`

---

**文档版本**: v1.0  
**最后更新**: 2026-08-06
