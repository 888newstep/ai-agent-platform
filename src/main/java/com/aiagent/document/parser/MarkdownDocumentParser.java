package com.aiagent.document.parser;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append("\n");
            }
            return text.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Markdown document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".md") || lowerName.endsWith(".txt");
    }
}
