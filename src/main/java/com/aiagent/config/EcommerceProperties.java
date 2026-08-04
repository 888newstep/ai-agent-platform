package com.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ecommerce")
public class EcommerceProperties {

    private Ollama ollama = new Ollama();
    private Milvus milvus = new Milvus();
    private Import importConfig = new Import();
    private Generator generator = new Generator();

    @Data
    public static class Ollama {
        private String host = "http://localhost:11434";
        private String model = "bge-m3";
        private int dimension = 1024;
    }

    @Data
    public static class Milvus {
        private String collection = "ecommerce_qa";
        private int dimension = 1024;
    }

    @Data
    public static class Import {
        private int batchSize = 18;
        private int batchIntervalMs = 200;
    }

    @Data
    public static class Generator {
        private int targetPerCategory = 500;
        private int batchSize = 20;
        private List<String> categories;
    }
}
