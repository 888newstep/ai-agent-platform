package com.aiagent.knowledge.infrastructure.parser;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int MAX_PAGES = 1000;
    private static final int MAX_EXTRACTED_CHARACTERS = 10_000_000;

    @Override
    public String parse(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("Encrypted PDF documents are not supported");
            }
            if (document.getNumberOfPages() > MAX_PAGES) {
                throw new IllegalArgumentException("PDF exceeds the page limit: " + MAX_PAGES);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.append("[Page ").append(page).append("]\n");
                text.append(stripper.getText(document)).append('\n');
                if (text.length() > MAX_EXTRACTED_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "PDF extracted text exceeds the character limit: " + MAX_EXTRACTED_CHARACTERS);
                }
            }
            return text.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PDF document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".pdf");
    }
}
