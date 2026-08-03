package com.aiagent.config;

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
    private Performance performance = new Performance();

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
        private Milvus milvus = new Milvus();
        private Pgvector pgvector = new Pgvector();
    }

    @Data
    public static class Milvus {
        private String host = "localhost";
        private int port = 19530;
        private String collectionName = "ai_agent_documents";
        private int dimension = 1024;
        private int connectionTimeoutMs = 2000;
    }

    @Data
    public static class Pgvector {
        private String host = "localhost";
        private int port = 5432;
        private String database = "ai_agent";
        private String tableName = "document_embeddings";
        private String username;
        private String password;
    }

    @Data
    public static class Document {
        private int chunkSize = 500;
        private int chunkOverlap = 50;
        private String supportedFormats = "pdf,docx,doc,md,txt";
    }

    @Data
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.7;
        private boolean enableHybridSearch = true;
    }

    @Data
    public static class Tool {
        private boolean enabled = true;
        private DatabaseQuery databaseQuery = new DatabaseQuery();
        private ApiCall apiCall = new ApiCall();
    }

    @Data
    public static class DatabaseQuery {
        private boolean enabled = true;
        private int maxRows = 100;
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
        private int maxResponseChars = 8000;
    }

    @Data
    public static class Session {
        private int ttl = 86400;
        private int maxMessages = 100;
        /** 滑动窗口大小（保留最近 N 轮对话，超出部分丢弃） */
        private int slidingWindowSize = 10;
        /** 摘要生成间隔（每 N 轮对话生成一次摘要） */
        private int summaryInterval = 5;
    }

    @Data
    public static class Security {
        private String adminApiKey;
        private String adminHeaderName = "X-Admin-Api-Key";
        private Jwt jwt = new Jwt();
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
        private boolean enabled = true;
        private int ttl = 3600;
    }
}
