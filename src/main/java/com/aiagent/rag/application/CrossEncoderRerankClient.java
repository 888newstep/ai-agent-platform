package com.aiagent.rag.application;

import java.util.List;

public interface CrossEncoderRerankClient {

    List<RerankScore> rerank(String query, List<String> documents);

    record RerankScore(int index, double score) {
    }
}
