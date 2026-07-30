package com.aiagent.document;

import com.aiagent.config.AiProperties;
import com.aiagent.document.parser.DocumentParserFactory;
import com.aiagent.document.splitter.TextSplitter;
import com.aiagent.vectorstore.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Qualifier("MilvusVectorStoreService")
public class DocumentService {

    private final DocumentParserFactory parserFactory;
    private final TextSplitter textSplitter;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final AiProperties aiProperties;

    public void uploadDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("Processing document: {}", fileName);

        try (InputStream inputStream = file.getInputStream()) {
            String content = parserFactory.parse(fileName, inputStream);
            processContent(fileName, content);
        } catch (Exception e) {
            log.error("Failed to process document", e);
            throw new RuntimeException("Failed to process document", e);
        }
    }

    public void processContent(String fileName, String content) {
        AiProperties.Document docConfig = aiProperties.getDocument();
        
        List<TextSegment> segments = textSplitter.split(
                content,
                docConfig.getChunkSize(),
                docConfig.getChunkOverlap()
        );

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            segment.metadata().put("fileName", fileName);
            segment.metadata().put("chunkIndex", i);
            segment.metadata().put("totalChunks", segments.size());
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        vectorStoreService.addAll(embeddings, segments);
        
        log.info("Document processed successfully. Total chunks: {}", segments.size());
    }

    public List<DocumentChunk> searchSimilar(String query, int topK, double threshold) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches = 
                vectorStoreService.search(queryEmbedding, topK, threshold);

        List<DocumentChunk> chunks = new ArrayList<>();
        for (var match : matches) {
            chunks.add(DocumentChunk.builder()
                    .id(match.embeddingId())
                    .content(match.embedded().text())
                    .score(match.score())
                    .metadata(match.embedded().metadata().toMap())
                    .build());
        }
        return chunks;
    }
}
