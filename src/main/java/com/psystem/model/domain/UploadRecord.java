package com.psystem.model.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Local ledger of upload attempts, persisted as plain JSON on the agent's working
 * directory (see {@code com.psystem.repository.UploadLedgerStore}). This is the backbone
 * of the idempotency strategy (see IdempotencyService) and also gives operators a
 * human-readable file for troubleshooting without needing network/S3 access or a database
 * client.
 * <p>
 * Plain POJO - no JPA/persistence annotations. Serialized to/from disk with Jackson via
 * {@link com.psystem.repository.UploadLedgerStore}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadRecord {

    /** Locally-generated identifier (UUID string) - no auto-increment DB behind this anymore. */
    private String id;

    /** Correlation id for the scheduler execution that produced this record. */
    private String executionId;

    /**
     * Deterministic key used for duplicate detection:
     * fileName + sizeBytes + lastModified(epoch millis) + machineId.
     * Deliberately does NOT include a full-file checksum by default - see README
     * "Idempotency" section for the cost/benefit trade-off on large files.
     * <p>
     * Unique per record - {@code UploadLedgerStore} keys its in-memory map on this field,
     * which is what the old H2 unique constraint on "fingerprint" used to enforce.
     */
    private String fingerprint;

    private String fileName;

    private long fileSizeBytes;

    private Instant fileLastModified;

    private String machineId;

    private UploadStatus status;

    private String s3ObjectKey;

    private String failureReason;

    private Instant createdAt;

    private Instant completedAt;

    public enum UploadStatus {
        IN_PROGRESS, SUCCESS, FAILED
    }
}
