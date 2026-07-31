package com.aiagent.document;

import com.aiagent.config.AiProperties;
import com.aiagent.document.parser.DocumentParserFactory;
import com.aiagent.document.splitter.TextSplitter;
import com.aiagent.entity.Document;
import com.aiagent.repository.DocumentChunkRepository;
import com.aiagent.repository.DocumentRepository;
import com.aiagent.vectorstore.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.transaction.Transactional;
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
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public void uploadDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("Processing document: {}", fileName);

        // 1. 保存文档元数据到 MySQL
        Document doc = Document.builder()
                .fileName(fileName)
                .fileType(extractFileType(fileName))
                .fileSize(file.getSize())
                .build();
        doc = documentRepository.save(doc);
        final Long documentId = doc.getId();

        try (InputStream inputStream = file.getInputStream()) {
            String content = parserFactory.parse(fileName, inputStream);
            processContent(fileName, content, documentId);
        } catch (Exception e) {
            log.error("Failed to process document [id={}]", documentId, e);
            throw new RuntimeException("Failed to process document", e);
        }
    }

    @Transactional
    public void processContent(String fileName, String content, Long documentId) {
        AiProperties.Document docConfig = aiProperties.getDocument();

        List<TextSegment> segments = textSplitter.split(
                content,
                docConfig.getChunkSize(),
                docConfig.getChunkOverlap()
        );

        // 1. 设置元数据（供 Milvus 检索和 MySQL 关联使用）
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            segment.metadata().put("fileName", fileName);
            segment.metadata().put("chunkIndex", i);
            segment.metadata().put("totalChunks", segments.size());
        }

        // 2. 批量生成 embedding
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 3. 批量写入 Milvus（单次 gRPC 调用，返回生成的 vector IDs）
        List<String> vectorIds = vectorStoreService.addAll(embeddings, segments);

        // 4. 批量写入 MySQL（单次 batch INSERT）
        List<com.aiagent.entity.DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            chunks.add(com.aiagent.entity.DocumentChunk.builder()
                    .documentId(documentId)
                    .chunkIndex(i)
                    .content(segments.get(i).text())
                    .charCount(segments.get(i).text().length())
                    .vectorId(i < vectorIds.size() ? vectorIds.get(i) : null)
                    .build());
        }
        documentChunkRepository.saveAll(chunks);

        // 5. 更新文档的 chunk 数量
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setChunkCount(segments.size());
            documentRepository.save(doc);
        });

        log.info("Document processed: {} chunks ({} Milvus + {} MySQL)",
                segments.size(), vectorIds.size(), chunks.size());
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

    private static String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}