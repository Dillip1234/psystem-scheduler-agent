package com.psystem.service.file;

import com.psystem.config.AgentProperties;
import com.psystem.exception.DirectoryUnavailableException;
import com.psystem.model.domain.CandidateFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Scans the configured source directory for candidate ZIP/RAR files and selects the single
 * largest one. Only regular, readable files with an allowed extension (case-insensitive)
 * are considered; everything else - subdirectories, symlinked directories, unsupported
 * extensions, unreadable files - is silently skipped (and logged at DEBUG).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileScannerService {

    private final AgentProperties properties;

    /**
     * Lists every eligible ZIP/RAR file in the configured directory.
     *
     * @throws DirectoryUnavailableException if the directory is missing, not a directory, or unreadable.
     */
    public List<CandidateFile> scan() {
        Path dir = validateAndResolveDirectory();
        List<CandidateFile> candidates = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                toCandidate(entry).ifPresent(candidates::add);
            }
        } catch (IOException e) {
            throw new DirectoryUnavailableException(
                    "Unable to list contents of source directory: " + dir, e);
        }

        log.info("Directory scan complete. path={} eligibleFilesFound={}", dir, candidates.size());
        return candidates;
    }

    /**
     * Scans and returns the largest eligible file, if any exist.
     */
    public Optional<CandidateFile> findLargest() {
        List<CandidateFile> candidates = scan();
        return candidates.stream()
                .max(Comparator.comparingLong(CandidateFile::getSizeBytes));
    }

    private Path validateAndResolveDirectory() {
        String configuredPath = properties.getFile().getSourceDirectory();
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new DirectoryUnavailableException("psystem.file.source-directory is not configured");
        }

        Path dir = Paths.get(configuredPath).toAbsolutePath().normalize();

        if (!Files.exists(dir)) {
            throw new DirectoryUnavailableException("Source directory does not exist: " + dir);
        }
        if (!Files.isDirectory(dir)) {
            throw new DirectoryUnavailableException("Configured source path is not a directory: " + dir);
        }
        if (!Files.isReadable(dir)) {
            throw new DirectoryUnavailableException("Source directory is not readable: " + dir);
        }
        return dir;
    }

    private Optional<CandidateFile> toCandidate(Path entry) {
        try {
            if (!Files.isRegularFile(entry)) {
                return Optional.empty();
            }
            if (!Files.isReadable(entry)) {
                log.warn("Skipping unreadable file: {}", entry.getFileName());
                return Optional.empty();
            }

            String fileName = entry.getFileName().toString();
            String matchedExtension = matchAllowedExtension(fileName);
            if (matchedExtension == null) {
                return Optional.empty();
            }

            long size = Files.size(entry);
            Instant lastModified = Files.getLastModifiedTime(entry).toInstant();

            return Optional.of(CandidateFile.builder()
                    .path(entry)
                    .fileName(fileName)
                    .sizeBytes(size)
                    .lastModified(lastModified)
                    .fileType(CandidateFile.FileType.fromExtension(matchedExtension))
                    .build());

        } catch (IOException e) {
            // A file can legitimately disappear between directory listing and stat() (race
            // condition with an external process). Log and skip rather than fail the whole scan.
            log.warn("Skipping file that became inaccessible during scan: {} ({})",
                    entry.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private String matchAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : properties.getFile().getAllowedExtensions()) {
            if (lower.endsWith(ext.toLowerCase())) {
                return ext;
            }
        }
        return null;
    }
}
