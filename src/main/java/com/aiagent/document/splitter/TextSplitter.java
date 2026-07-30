package com.aiagent.document.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TextSplitter {

    private final OpenAiTokenizer tokenizer;

    public List<TextSegment> split(String text, int chunkSize, int chunkOverlap) {
        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(
                chunkSize,
                chunkOverlap,
                tokenizer
        );
        return splitter.split(document);
    }
}
