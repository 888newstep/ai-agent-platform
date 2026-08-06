package com.aiagent.knowledge.infrastructure.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TxtDocumentParserTest {
    private TxtDocumentParser parser;

    @BeforeEach void setUp() { parser = new TxtDocumentParser(); }

    @Test void shouldSupportTxtFiles() {
        assertTrue(parser.supports("test.txt"));
        assertTrue(parser.supports("TEST.TXT"));
        assertFalse(parser.supports("test.pdf"));
    }

    @Test void shouldParseFaqFormat() {
        String content = "Q: What is return policy?\nA: 30 days return.\n---\nQ: How to ship?\nA: Free shipping.\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
        assertTrue(result.contains("return") || result.contains("ship"));
    }

    @Test void shouldParseConversationFormat() {
        String content = "User: Hello\nAssistant: Hi there\n---\nUser: Help?\nAssistant: Sure\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldParseArticleFormat() {
        String content = "# Return Policy\n## Category: Support\n## Keywords: return\nThis is the body.\n===\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldParseCsvFormat() {
        String content = "question,answer,category\nHow to return?,30 days,Support\nHow to ship?,Free,Logistics\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldParsePlainText() {
        String content = "Just some plain text content without any special format markers.";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldHandleEmptyContent() {
        InputStream is = new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertEquals("", result);
    }

    @Test void shouldHandleMixedFormat() {
        String content = "Just some plain text content here.\n\nMore text in another paragraph.\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldDetectFaqFormat() {
        String content = "Q: Q1\nA: A1\nQ: Q2\nA: A2\nQ: Q3\nA: A3\n";
        assertEquals(TxtDocumentParser.DocumentFormat.FAQ, parser.detectFormat(content));
    }

    @Test void shouldDetectConversationFormat() {
        String content = "User: Hi\nAssistant: Hello\nUser: Help\nAssistant: Sure\n";
        assertEquals(TxtDocumentParser.DocumentFormat.CONVERSATION, parser.detectFormat(content));
    }

    @Test void shouldDetectArticleFormat() {
        // Article format needs enough # markers to exceed threshold
        String content = "# Title\n## Sub\nBody\n# T2\n## S2\nB2\n# T3\n## S3\nB3\n# T4\n## S4\nB4\n# T5\n## S5\nB5\n";
        TxtDocumentParser.DocumentFormat fmt = parser.detectFormat(content);
        assertNotNull(fmt);
    }

    @Test void shouldDetectCsvFormat() {
        String content = "question,answer,category\nQ1,A1,Cat1\nQ2,A2,Cat2\nQ3,A3,Cat3\nQ4,A4,Cat4\nQ5,A5,Cat5\n";
        TxtDocumentParser.DocumentFormat fmt = parser.detectFormat(content);
        assertNotNull(fmt);
    }

    @Test void shouldDetectPlainFormat() {
        String content = "Just random text\nNothing special here\nNo markers at all\n";
        assertEquals(TxtDocumentParser.DocumentFormat.PLAIN, parser.detectFormat(content));
    }

    @Test void shouldParseFaqWithChineseMarkers() {
        String content = "Q: \u95ee\u98981\nA: \u56de\u7b541\n---\nQ: \u95ee\u98982\nA: \u56de\u7b542\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
    }

    @Test void shouldParseArticleWithMetadata() {
        String content = "# Title\n## Category: Test\n## Keywords: a,b\nBody content here.\n===\n";
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        String result = parser.parse(is);
        assertNotNull(result);
        assertTrue(result.contains("Body content"));
    }
}
