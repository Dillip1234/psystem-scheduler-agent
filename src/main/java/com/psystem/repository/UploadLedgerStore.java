package com.psystem.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psystem.config.AgentProperties;
import com.psystem.model.domain.UploadRecord;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-file replacement for the old H2/JPA-backed {@code UploadRecordRepository}.
 * <p>
 * Holds the entire upload ledger as a single JSON array on disk (default
 * {@code ./.psystem/ledger.json}), mirrored in an in-memory map keyed by
 * {@code fingerprint} - the same field the old H2 table had a unique constraint on, and
 * the same field every lookup in {@code IdempotencyService} uses. There is deliberately no
 * JDBC connection, connection pool, or embedded database server involved: the agent runs
 * as a single instance per machine (see {@code SchedulerLockService} / WinSW), so there is
 * no concurrent-writer scenario that would justify one.
 * <p>
 * <b>Durability:</b> every {@link #save(UploadRecord)} rewrites the whole file. To avoid
 * leaving a half-written, corrupt JSON file behind if the process is killed mid-write
 * (the same restart-safety concern the H2 ledger existed for), the new contents are written
 * to a temp file first and then atomically moved over the real file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadLedgerStore {

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    /** In-memory index, keyed by fingerprint - one record per fingerprint, same as the old unique constraint. */
    private final Map<String, UploadRecord> byFingerprint = new LinkedHashMap<>();

    private Path ledgerFilePath;

    @PostConstruct
    synchronized void load() {
        ledgerFilePath = Path.of(properties.getLedger().getFilePath());
        File file = ledgerFilePath.toFile();

        if (!file.exists()) {
            log.info("No existing ledger file at {} - starting with an empty ledger.", ledgerFilePath);
            return;
        }

        try {
            List<UploadRecord> loaded = objectMapper.readValue(file, new TypeReference<List<UploadRecord>>() {});
            for (UploadRecord record : loaded) {
                byFingerprint.put(record.getFingerprint(), record);
            }
            log.info("Loaded {} record(s) from ledger file {}.", byFingerprint.size(), ledgerFilePath);
        } catch (IOException e) {
            // Deliberately does not fail startup: a corrupt/unreadable ledger file should not stop the
            // agent from running (it would rather re-upload a file than refuse to run at all). This does
            // mean any duplicate-detection history is lost until the file is fixed or replaced.
            log.error("Failed to read ledger file {} - starting with an empty ledger. " +
                    "Duplicate-detection history from before this point is unavailable until this is resolved.",
                    ledgerFilePath, e);
        }
    }

    public synchronized boolean existsByFingerprintAndStatus(String fingerprint, UploadRecord.UploadStatus status) {
        UploadRecord record = byFingerprint.get(fingerprint);
        return record != null && record.getStatus() == status;
    }

    public synchronized Optional<UploadRecord> findByFingerprint(String fingerprint) {
        return Optional.ofNullable(byFingerprint.get(fingerprint));
    }

    public synchronized UploadRecord save(UploadRecord record) {
        if (record.getId() == null) {
            record.setId(UUID.randomUUID().toString());
        }
        byFingerprint.put(record.getFingerprint(), record);
        persist();
        return record;
    }

    private void persist() {
        try {
            Files.createDirectories(ledgerFilePath.toAbsolutePath().getParent());

            Path tempFile = ledgerFilePath.resolveSibling(ledgerFilePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tempFile.toFile(), new ArrayList<>(byFingerprint.values()));

            Files.move(tempFile, ledgerFilePath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Surfacing this as unchecked: a failed ledger write means we can no longer guarantee
            // idempotency for this execution, so the caller (IdempotencyService) should see it fail
            // rather than silently continue as if the record was durably saved.
            throw new UploadLedgerPersistenceException(
                    "Failed to persist upload ledger to " + ledgerFilePath, e);
        }
    }

    public static class UploadLedgerPersistenceException extends RuntimeException {
        public UploadLedgerPersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
