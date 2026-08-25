package com.aiagent.shared.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Parses the shared system/user/assistant JSONL shape used by data generation and import flows.
 */
public final class TrainingQaParser {

    private TrainingQaParser() {
    }

    public static Optional<TrainingQa> parse(ObjectMapper objectMapper, String jsonLine)
            throws JsonProcessingException {
        JsonNode messages = objectMapper.readTree(jsonLine).path("messages");
        if (!messages.isArray() || messages.size() < 3) {
            return Optional.empty();
        }

        String systemPrompt = "";
        String question = "";
        String answer = "";
        for (JsonNode message : messages) {
            String role = textValue(message, "role");
            String content = textValue(message, "content");
            switch (role) {
                case "system" -> systemPrompt = content;
                case "user" -> question = content;
                case "assistant" -> answer = content;
                default -> {
                    // Ignore unsupported roles in training data.
                }
            }
        }

        return Optional.of(new TrainingQa(
                systemPrompt,
                normalizeText(question),
                normalizeText(answer)));
    }

    public static String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String textValue(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isTextual() ? value.textValue() : "";
    }

    public record TrainingQa(String systemPrompt, String question, String answer) {

        public boolean hasQuestion() {
            return !question.isEmpty();
        }

        public boolean isComplete() {
            return hasQuestion() && !answer.isEmpty();
        }

        public String embeddingText() {
            return "用户问题：" + question + " 客服回答：" + answer;
        }
    }
}
