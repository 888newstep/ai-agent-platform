package com.aiagent.knowledge.infrastructure.parser;

import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final int MAX_TEXT_BYTES = 20 * 1024 * 1024;

    @Override
    public String parse(InputStream inputStream) {
        try {
            return BoundedTextReader.read(inputStream, MAX_TEXT_BYTES);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Markdown document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".md") || lowerName.endsWith(".markdown");
    }
}
