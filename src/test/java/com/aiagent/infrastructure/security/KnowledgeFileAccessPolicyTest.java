package com.aiagent.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeFileAccessPolicyTest {

    @TempDir
    Path tempDir;

    private final KnowledgeFileAccessPolicy policy = new KnowledgeFileAccessPolicy();

    @Test
    void acceptsConfiguredJsonlFile() throws Exception {
        Path file = Files.writeString(tempDir.resolve("training.jsonl"), "{}\n");

        Path resolved = policy.requireAllowedRegularFile(
                tempDir.toString(), "training.jsonl", Set.of(".jsonl"));

        assertThat(resolved).isEqualTo(file.toRealPath());
    }

    @Test
    void rejectsTraversalAndUnsupportedExtensions() throws Exception {
        Files.writeString(tempDir.resolve("training.txt"), "data");

        assertThatThrownBy(() -> policy.requireAllowedRegularFile(
                tempDir.toString(), "../training.jsonl", Set.of(".jsonl")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowedRegularFile(
                tempDir.toString(), "training.txt", Set.of(".jsonl")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
