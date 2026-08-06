package com.aiagent.knowledge.infrastructure.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TxtDocumentParserAdditionalTest {
    private TxtDocumentParser parser;
    @BeforeEach void setUp() { parser = new TxtDocumentParser(); }

    @Test void shouldParseMultipleFaqEntries() {
        String c = "Q: Q1?\nA: A1.\n---\nQ: Q2?\nA: A2.\n---\nQ: Q3?\nA: A3.\n---\nQ: Q4?\nA: A4.\n---\nQ: Q5?\nA: A5.\n";
        String r = parser.parse(new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r); assertTrue(r.length() > 0);
    }
    @Test void shouldParseMultipleConversationTurns() {
        String c = "User: Hi\nAssistant: Hello\nUser: How?\nAssistant: Good\n---\nUser: Bye\nAssistant: Bye\n";
        String r = parser.parse(new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
    }
    @Test void shouldParseMultipleArticles() {
        String c = "# T1\n## Category: C1\nBody1\n===\n# T2\n## Category: C2\nBody2\n===\n";
        String r = parser.parse(new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
    }
    @Test void shouldParseCsvWithMultipleRows() {
        String c = "question,answer,category\nQ1,A1,C1\nQ2,A2,C2\nQ3,A3,C3\nQ4,A4,C4\nQ5,A5,C5\n";
        String r = parser.parse(new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
    }
    @Test void shouldParseLongPlainText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("Line ").append(i).append(" of text.\n");
        String r = parser.parse(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r); assertTrue(r.length() > 100);
    }
    @Test void shouldParseChineseFaq() {
        String c = "Q: \u5982\u4f55\u9000\u8d27\uff1f\nA: \u8bf7\u5728\u8ba2\u5355\u9875\u7533\u8bf7\u3002\n---\nQ: \u7269\u6d41\u591a\u4e45\uff1f\nA: 3-5\u5929\u3002\n";
        String r = parser.parse(new ByteArrayInputStream(c.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
    }
}
