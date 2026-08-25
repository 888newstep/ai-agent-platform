package com.aiagent.knowledge.infrastructure.storage;

import com.aiagent.infrastructure.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
public class DocumentStagingStorage {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Path stagingDirectory;

    public DocumentStagingStorage(AiProperties aiProperties) {
        this.stagingDirectory = Path.of(aiProperties.getDocument().getStagingDirectory())
                .toAbsolutePath()
                .normalize();
    }

    public StagedDocument stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded document must not be empty");
        }

        Path temporaryFile = null;
        try {
            Files.createDirectories(stagingDirectory);
            temporaryFile = Files.createTempFile(stagingDirectory, ".document-upload-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyAndDigest(file, temporaryFile, digest);
            if (size == 0) {
                throw new IllegalArgumentException("Uploaded document must not be empty");
            }

            String storedName = UUID.randomUUID() + ".staged";
            Path finalFile = stagingDirectory.resolve(storedName);
            moveAtomically(temporaryFile, finalFile);
            return new StagedDocument(storedName, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            deleteQuietly(temporaryFile);
            throw new IllegalStateException("Failed to stage uploaded document", exception);
        } catch (NoSuchAlgorithmException exception) {
            deleteQuietly(temporaryFile);
            throw new IllegalStateException("SHA-256 is not available", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temporaryFile);
            throw exception;
        }
    }

    public InputStream open(String storedPath) throws IOException {
        Path path = resolve(storedPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Staged document does not exist: " + storedPath);
        }
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    public void delete(String storedPath) throws IOException {
        if (storedPath != null && !storedPath.isBlank()) {
            Files.deleteIfExists(resolve(storedPath));
        }
    }

    public boolean exists(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        return Files.isRegularFile(resolve(storedPath));
    }

    public void deleteQuietly(String storedPath) {
        try {
            delete(storedPath);
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to delete staged document [{}]: {}", storedPath, exception.getMessage());
        }
    }

    Path resolve(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("Staging path must not be blank");
        }
        Path candidate = stagingDirectory.resolve(storedPath).normalize();
        if (!candidate.startsWith(stagingDirectory)) {
            throw new IllegalArgumentException("Staging path escapes the configured directory");
        }
        return candidate;
    }

    private long copyAndDigest(MultipartFile file, Path target, MessageDigest digest) throws IOException {
        long totalBytes = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream inputStream = file.getInputStream();
             OutputStream outputStream = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                if (bytesRead == 0) {
                    continue;
                }
                outputStream.write(buffer, 0, bytesRead);
                digest.update(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
        }
        return totalBytes;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Failed to clean temporary upload [{}]: {}", path, exception.getMessage());
        }
    }

    public record StagedDocument(String storedPath, long size, String contentHash) {
    }
}
