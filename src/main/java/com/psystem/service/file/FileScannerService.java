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
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Scans the configured source directory for candidate ZIP/RAR files and selects the single
 * largest one. Only regular, readable files with an allowed extension (case-insensitive)
 * are considered; everything else - subdirectories, symlinked directories, unsupported
 * extensions, unreadable files - is silently skipped (and logged at DEBUG).
 * <p>
 * When {@code psystem.file.today-only} is true (the default), a file is also required to have
 * been <b>created today</b> (agent machine's local date), so the "largest file" is always the
 * largest of today's files, never an older, bigger leftover.
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

        // Resolve "today" once per scan so every file is compared against the same date,
        // even if the scan happens to straddle midnight.
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = properties.getFile().isTodayOnly() ? LocalDate.now(zone) : null;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                toCandidate(entry, today, zone).ifPresent(candidates::add);
            }
        } catch (IOException e) {
            throw new DirectoryUnavailableException(
                    "Unable to list contents of source directory: " + dir, e);
        }

        log.info("Directory scan complete. path={} todayOnly={} date={} eligibleFilesFound={}",
                dir, today != null, today, candidates.size());
        return candidates;
    }

    /**
     * Scans and returns the largest eligible file, if any exist. If two files have exactly the
     * same size, the more recently created one wins so the choice is deterministic.
     */
    public Optional<CandidateFile> findLargest() {
        List<CandidateFile> candidates = scan();
        return candidates.stream()
                .max(Comparator.comparingLong(CandidateFile::getSizeBytes)
                        .thenComparing(CandidateFile::getCreatedAt));
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

    private Optional<CandidateFile> toCandidate(Path entry, LocalDate today, ZoneId zone) {
        try {
            // One attribute read gives type, size, creation and modified time together,
            // so all values are a consistent snapshot of the same moment.
            BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                return Optional.empty();
            }

            String fileName = entry.getFileName().toString();
            String matchedExtension = matchAllowedExtension(fileName);
            if (matchedExtension == null) {
                return Optional.empty();
            }

            Instant createdAt = attrs.creationTime().toInstant();
            if (today != null) {
                LocalDate createdDate = createdAt.atZone(zone).toLocalDate();
                if (!createdDate.equals(today)) {
                    log.debug("Skipping {} - created {} (not today {})", fileName, createdDate, today);
                    return Optional.empty();
                }
            }

            if (!Files.isReadable(entry)) {
                log.warn("Skipping unreadable file: {}", fileName);
                return Optional.empty();
            }

            return Optional.of(CandidateFile.builder()
                    .path(entry)
                    .fileName(fileName)
                    .sizeBytes(attrs.size())
                    .lastModified(attrs.lastModifiedTime().toInstant())
                    .createdAt(createdAt)
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
