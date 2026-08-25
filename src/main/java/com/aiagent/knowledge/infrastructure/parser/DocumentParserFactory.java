package com.aiagent.knowledge.infrastructure.parser;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;

    public boolean supports(String fileName) {
        return parsers.stream().anyMatch(parser -> parser.supports(fileName));
    }

    public String parse(String fileName, InputStream inputStream) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName)) {
                return parser.parse(inputStream);
            }
        }
        throw new IllegalArgumentException("Unsupported document format: " + fileName);
    }
}
