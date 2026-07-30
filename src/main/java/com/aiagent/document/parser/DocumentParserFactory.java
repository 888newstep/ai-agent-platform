package com.aiagent.document.parser;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentParserFactory {

    private final List<DocumentParser> parsers;

    public String parse(String fileName, InputStream inputStream) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName)) {
                return parser.parse(inputStream);
            }
        }
        throw new IllegalArgumentException("Unsupported document format: " + fileName);
    }
}
