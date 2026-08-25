package com.aiagent.infrastructure.security;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class KnowledgeFileAccessPolicy {

    public Path requireAllowedRegularFile(String directoryPath,
                                          String fileName,
                                          Set<String> allowedExtensions) {
        validateSimpleFileName(fileName);
        Path directory = requireAllowedDirectory(directoryPath);
        Path candidate = directory.resolve(fileName).normalize();
        if (!candidate.startsWith(directory) || !Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Knowledge file does not exist or is not a regular file");
        }

        Path realPath = toRealPath(candidate);
        if (!realPath.startsWith(directory) || !Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("Knowledge file is outside the configured data directory");
        }
        validateExtension(realPath, allowedExtensions);
        return realPath;
    }

    public Path requireAllowedDirectory(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            throw new IllegalArgumentException("Knowledge data directory is not configured");
        }
        final Path directory;
        try {
            directory = Path.of(directoryPath).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Knowledge data directory is invalid", exception);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Knowledge data directory does not exist");
        }
        Path realDirectory = toRealPath(directory);
        if (!Files.isDirectory(realDirectory)) {
            throw new IllegalArgumentException("Knowledge data directory is not a directory");
        }
        return realDirectory;
    }

    private void validateSimpleFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("/") || fileName.contains("\\") || fileName.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Knowledge file name must be a single safe path segment");
        }
    }

    private void validateExtension(Path path, Set<String> allowedExtensions) {
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean allowed = allowedExtensions.stream()
                .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .anyMatch(fileName::endsWith);
        if (!allowed) {
            throw new IllegalArgumentException("Knowledge file type is not allowed");
        }
    }

    private Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Knowledge file path cannot be resolved", exception);
        }
    }
}
