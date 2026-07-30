package com.aiagent.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class WordDocumentParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder text = new StringBuilder();
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (XWPFParagraph paragraph : paragraphs) {
                text.append(paragraph.getText()).append("\n");
            }
            return text.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Word document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".docx") || lowerName.endsWith(".doc");
    }
}
