package com.aiagent.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Model model = new Model();
    private Embedding embedding = new Embedding();
    private VectorStore vectorStore = new VectorStore();
    private Document document = new Document();
    private Rag rag = new Rag();
    private Tool tool = new Tool();
    private Session session = new Session();
    private Security security = new Security();
    private Observability observability = new Observability();
    private Performance performance = new Performance();
    private Protection protection = new Protection();

    @Data
    public static class Model {
        private String provider = "deepseek";
        private Deepseek deepseek = new Deepseek();
        private Qianwen qianwen = new Qianwen();
        private Doubao doubao = new Doubao();
        private Qwen3Flash qwen3Flash = new Qwen3Flash();
        private Local local = new Local();
    }

    @Data
    public static class Deepseek {
        private String apiKey;
        private String modelName = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com/v1";
    }

    @Data
    public static class Qianwen {
        private String apiKey;
        private String modelName = "qwen-max";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    @Data
    public static class Doubao {
        private String apiKey;
        private String modelName = "doubao-seed-2-0-mini-260428";
        private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";
    }

    @Data
    public static class Qwen3Flash {
        private String apiKey;
        private String modelName = "qwen3.7-flash";
        private String baseUrl = "https://ws-saj0dk2icyo8g1ub.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
    }

    @Data
    public static class Local {
        private String baseUrl = "http://localhost:11434/v1";
        private String modelName = "llama2";
        private String apiKey = "dummy";
    }

    @Data
    public static class Embedding {
        private String provider = "deepseek";
        private DeepseekEmbedding deepseek = new DeepseekEmbedding();
        private QianwenEmbedding qianwen = new QianwenEmbedding();
        private LocalEmbedding local = new LocalEmbedding();
        private LocalEmbedding localQwen3 = new LocalEmbedding();
        private SiliconflowEmbedding siliconflow = new SiliconflowEmbedding();
    }

    @Data
    public static class SiliconflowEmbedding {
        private String apiKey;
        private String modelName = "BAAI/bge-m3";
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private int dimension = 1024;
    }

    @Data
    public static class DeepseekEmbedding {
        private String apiKey;
        private String modelName = "deepseek-embed";
        private int dimension = 1024;
    }

    @Data
    public static class QianwenEmbedding {
        private String apiKey;
        private String modelName = "text-embedding-v3";
        private int dimension = 1024;
    }

    @Data
    public static class LocalEmbedding {
        private String baseUrl = "http://localhost:11434/v1";
        private String modelName = "nomic-embed-text";
        private int dimension = 768;
    }

    @Data
    public static class VectorStore {
        private String type = "milvus";
        private String mode = "langchain";
        private Milvus milvus = new Milvus();
    }

    @Data
    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String databaseName = "cs_agent";
        private String collectionName = "ai_agent_documents";
        private int dimension = 1024;
        private int connectionTimeoutMs = 2000;
        private boolean readOnly = false;
        private boolean fallbackEnabled = false;
    }

    /** Document ingestion and chunking settings. */
    @Data
    public static class Document {
        private int chunkSize = 500;
        private int chunkOverlap = 50;
        private String stagingDirectory = "./data/staging";
        private String parserVersion = "v1";
        private int ingestionCoreSize = 2;
        private int ingestionMaxSize = 4;
        private int ingestionQueueCapacity = 100;
    }

    /** Retrieval settings shared by classic and adaptive RAG. */
    @Data
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.7;
        /** 混合检索仅在评测证明有效后开启；默认使用当前更稳定的向量基线。 */
        private boolean enableHybridSearch = false;
        /** RRF 中向量和 BM25 路由的权重。向量优先，避免通用中文词引入噪声。 */
        private double hybridVectorWeight = 0.95;
        private double hybridBm25Weight = 0.05;
        private int hybridRrfK = 60;
        private int hybridVectorCandidateTopK = 20;
        private int hybridBm25CandidateTopK = 20;
        private int hybridBm25CorpusMaxDocs = 5000;
        private boolean hybridCorpusBm25Enabled = false;
        private boolean bm25StopwordEnabled = true;
        private Adaptive adaptive = new Adaptive();
    }

    /** Controls the router -> rewrite -> retrieve -> verify loop. */
    @Data
    public static class Adaptive {
        /** Enables adaptive routing instead of unconditional retrieval. */
        private boolean enabled = true;
        /** Maximum number of retrieval rounds allowed in one request. */
        private int maxRetrievalRounds = 2;
        /** Maximum number of chunks injected into the final model context. */
        private int maxContextChunks = 5;
        /** Minimum combined verification score for single-hop retrieval to be accepted. */
        private double verificationThreshold = 0.72;
        /** Minimum combined verification score for multi-hop retrieval to be accepted. */
        private double multiHopThreshold = 0.78;
        /** Heuristic threshold reserved for future direct-answer routing tuning. */
        private double directThreshold = 0.45;
        /** Minimum number of keywords preserved during query rewriting. */
        private int minKeywordCount = 2;
        /** Enables a second semantic scoring pass over recalled chunks. */
        private boolean semanticRerankEnabled = true;
        /** Reranker implementation: embedding or cross-encoder. */
        private String rerankProvider = "embedding";
        /** Maximum chunks retained after semantic reranking. */
        private int semanticRerankTopK = 5;
        /** Minimum cosine score required for a chunk to reach verification. */
        private double semanticRerankMinScore = 0.62;
        /** Minimum weighted keyword coverage required for sufficient evidence. */
        private double minimumKeywordCoverage = 0.60;
        /** Enables post-generation answer-to-evidence support checking. */
        private boolean answerSupportEnabled = true;
        /** Minimum semantic score for an answer claim to be supported. */
        private double answerSupportMinScore = 0.68;
        /** Minimum proportion of answer claims that must be supported. */
        private double answerSupportMinRatio = 0.8;
        /** Cross-encoder HTTP reranker settings. */
        private CrossEncoder crossEncoder = new CrossEncoder();
    }

    @Data
    public static class CrossEncoder {
        private String endpoint = "https://api.siliconflow.cn/v1/rerank";
        private String apiKey;
        private String modelName = "BAAI/bge-reranker-v2-m3";
        private double minScore = 0.55;
        private int timeoutMs = 10_000;
        private int maxDocumentCharacters = 4_000;
        private boolean fallbackToEmbedding = false;
    }

    @Data
    public static class Tool {
        private DatabaseQuery databaseQuery = new DatabaseQuery();
        private ApiCall apiCall = new ApiCall();
    }

    @Data
    public static class DatabaseQuery {
        private boolean enabled = true;
        private int maxRows = 100;
        private int queryTimeoutSeconds = 5;
        private List<String> allowedTables = List.of(
                "conversations",
                "messages",
                "documents",
                "document_chunks",
                "users",
                "ecommerce_qa_pairs",
                "ecommerce_feedback",
                "message_classify_log",
                "vision_analysis_cache"
        );
    }

    @Data
    public static class ApiCall {
        private boolean enabled = true;
        private int timeout = 30000;
        private List<String> allowedHosts = List.of();
        private List<String> allowedMethods = List.of("GET", "POST");
        private int maxRequestChars = 8000;
        private int maxResponseChars = 8000;
    }

    /** Session memory settings for short-term context and summarization. */
    @Data
    public static class Session {
        private int ttl = 86400;
        private int maxMessages = 100;
        /** Number of recent dialogue turns kept in the sliding window. */
        private int slidingWindowSize = 10;
        /** Generate one summary after every N dialogue turns. */
        private int summaryInterval = 5;
    }

    @Data
    public static class Protection {
        private RateLimit rateLimit = new RateLimit();
        private CostBudget costBudget = new CostBudget();
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 30;
        private int authenticationRequestsPerMinute = 10;
        private boolean failOpen = true;
    }

    @Data
    public static class CostBudget {
        private boolean enabled = true;
        private long estimatedTokensPerMinute = 12000;
        private long maxEstimatedTokensPerRequest = 4000;
        private int maxInputCharacters = 12000;
        private double tokensPerCharacter = 1.0;
        private long promptOverheadTokens = 256;
        private boolean failOpen = true;
    }
    @Data
    public static class Security {
        private String adminApiKey;
        private String adminHeaderName = "X-Admin-Api-Key";
        private Jwt jwt = new Jwt();
        private Cors cors = new Cors();
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:8081");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of("Authorization", "Content-Type", "Accept", "X-Admin-Api-Key", "X-Requested-With");
        private boolean allowCredentials = false;
        private long maxAge = 3600;
    }

    @Data
    public static class Observability {
        private boolean publicMetrics = false;
    }

    @Data
    public static class Jwt {
        private String secret;
        private long expiration = 86400000;
    }

    @Data
    public static class Performance {
        private AsyncThreadPool asyncThreadPool = new AsyncThreadPool();
        private Cache cache = new Cache();
    }

    @Data
    public static class AsyncThreadPool {
        private int coreSize = 20;
        private int maxSize = 100;
        private int queueCapacity = 200;
    }

    @Data
    public static class Cache {
        private int ttl = 3600;
    }
}
