package com.aiagent.knowledge.infrastructure.parser;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class WordDocumentParser implements DocumentParser {

    private static final int MAX_EXTRACTED_CHARACTERS = 10_000_000;

    static {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(MAX_EXTRACTED_CHARACTERS);
    }

    @Override
    public String parse(InputStream inputStream) {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder text = new StringBuilder();
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (XWPFParagraph paragraph : paragraphs) {
                text.append(paragraph.getText()).append("\n");
                enforceCharacterLimit(text);
            }
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (int index = 0; index < row.getTableCells().size(); index++) {
                        XWPFTableCell cell = row.getCell(index);
                        if (index > 0) {
                            text.append(" | ");
                        }
                        text.append(cell.getText());
                    }
                    text.append('\n');
                    enforceCharacterLimit(text);
                }
            }
            return text.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Word document", e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".docx");
    }

    private void enforceCharacterLimit(StringBuilder text) {
        if (text.length() > MAX_EXTRACTED_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Word extracted text exceeds the character limit: " + MAX_EXTRACTED_CHARACTERS);
        }
    }
}
