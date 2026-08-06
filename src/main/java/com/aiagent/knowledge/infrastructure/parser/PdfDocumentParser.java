package com.aiagent.knowledge.infrastructure.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream) {
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse PDF document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".pdf");
    }
}
