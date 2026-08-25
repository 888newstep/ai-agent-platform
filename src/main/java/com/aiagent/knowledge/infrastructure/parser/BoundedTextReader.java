package com.aiagent.knowledge.infrastructure.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class BoundedTextReader {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private BoundedTextReader() {
    }

    static String read(InputStream inputStream, int maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            if (bytesRead == 0) {
                continue;
            }
            totalBytes += bytesRead;
            if (totalBytes > maxBytes) {
                throw new IllegalArgumentException("Text document exceeds the parser byte limit: " + maxBytes);
            }
            outputStream.write(buffer, 0, bytesRead);
        }
        return decode(outputStream.toByteArray());
    }

    private static String decode(byte[] bytes) {
        if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return new String(bytes, GB18030);
        }
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
