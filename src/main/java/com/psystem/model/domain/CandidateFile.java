package com.psystem.model.domain;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Immutable snapshot of a single ZIP/RAR file discovered on disk at scan time.
 * Captured as a value object so that later stability checks compare against a
 * frozen reference rather than re-reading mutable filesystem state implicitly.
 */
@Value
@Builder(toBuilder = true)
public class CandidateFile {
    Path path;
    String fileName;
    long sizeBytes;
    Instant lastModified;
    /** File system creation time (on Linux/some file systems this may fall back to last-modified). */
    Instant createdAt;
    FileType fileType;

    public enum FileType {
        ZIP, RAR;

        public static FileType fromExtension(String extension) {
            String normalized = extension.toLowerCase();
            if (normalized.endsWith("zip")) return ZIP;
            if (normalized.endsWith("rar")) return RAR;
            throw new IllegalArgumentException("Unsupported extension: " + extension);
        }
    }
}
