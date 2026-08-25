package com.aiagent.rag.application;

import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcommerceQaLexicalSearchService {

    private static final int MAX_QUERY_CHARACTERS = 1024;

    private final EcommerceQaPairRepository repository;
    private final AiProperties aiProperties;

    public List<RetrievalChunk> search(String query, int topK) {
        if (!isEnabled() || !StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }

        String normalizedQuery = query.trim();
        if (normalizedQuery.length() > MAX_QUERY_CHARACTERS) {
            normalizedQuery = normalizedQuery.substring(0, MAX_QUERY_CHARACTERS);
        }

        try {
            return repository.searchQuestionFullText(normalizedQuery, topK).stream()
                    .map(this::toChunk)
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("MySQL QA full-text retrieval unavailable: {}", rootMessage(exception));
            return List.of();
        }
    }

    private boolean isEnabled() {
        return aiProperties.getRag().isHybridMysqlFulltextEnabled()
                && "qa".equalsIgnoreCase(aiProperties.getVectorStore().getMode());
    }

    private RetrievalChunk toChunk(EcommerceQaPairRepository.LexicalCandidate candidate) {
        double score = candidate.getLexicalScore() == null ? 0.0 : candidate.getLexicalScore();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("question", nullToEmpty(candidate.getQuestion()));
        metadata.put("answer", nullToEmpty(candidate.getAnswer()));
        metadata.put("category", nullToEmpty(candidate.getCategory()));
        metadata.put("lexicalScore", score);
        metadata.put("lexicalProvider", "mysql_ngram_fulltext");

        String content = StringUtils.hasText(candidate.getQaText())
                ? candidate.getQaText()
                : "用户问题：" + nullToEmpty(candidate.getQuestion())
                        + " 客服回答：" + nullToEmpty(candidate.getAnswer());
        return RetrievalChunk.builder()
                .id(String.valueOf(candidate.getId()))
                .content(content)
                .score(score)
                .metadata(metadata)
                .build();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
