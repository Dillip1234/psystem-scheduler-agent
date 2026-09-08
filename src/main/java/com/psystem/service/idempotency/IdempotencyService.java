package com.psystem.service.idempotency;

import com.psystem.model.domain.CandidateFile;
import com.psystem.model.domain.MachineMetadata;
import com.psystem.model.domain.UploadRecord;
import com.psystem.repository.UploadLedgerStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Duplicate-upload prevention.
 * <p>
 * <b>Fingerprint strategy:</b> {@code fileName + fileSizeBytes + lastModified(epoch ms) + machineId}.
 * This combination is a strong-enough duplicate signal for this use case without paying the
 * cost of hashing the full file:
 * <ul>
 *   <li>A file that was already successfully uploaded will not change its name, size, or
 *       mtime unless it is genuinely a new/different backup - in which case at least one
 *       of those three values changes and it is correctly treated as new.</li>
 *   <li>Computing a SHA-256 checksum of a 500MB+ file on every scheduled run adds
 *       meaningful I/O and CPU overhead on a customer machine that may be modest hardware,
 *       for a duplicate-detection benefit that name+size+mtime already provides in
 *       practice. We therefore do NOT checksum by default.</li>
 *   <li>A full checksum remains available as a defense-in-depth option (see
 *       {@code psystem.file.checksum-verification-enabled}, left as an extension point) for
 *       customers who need cryptographic certainty, accepting the extra scan time.</li>
 * </ul>
 * <p>
 * <b>State machine:</b> a record is written as {@code IN_PROGRESS} before the pre-signed URL
 * is even requested. If the application restarts mid-upload, the next scheduled run will find
 * a stale {@code IN_PROGRESS} record for a file whose upload never completed; because it is
 * not {@code SUCCESS}, the file is eligible for upload again - preventing both silent data
 * loss (never retrying) and silent duplication (endlessly re-uploading a completed file).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final UploadLedgerStore repository;

    public String fingerprint(CandidateFile file, MachineMetadata metadata) {
        return String.join("|",
                file.getFileName(),
                String.valueOf(file.getSizeBytes()),
                String.valueOf(file.getLastModified().toEpochMilli()),
                metadata.getMachineId());
    }

    public boolean isAlreadyUploaded(String fingerprint) {
        return repository.existsByFingerprintAndStatus(fingerprint, UploadRecord.UploadStatus.SUCCESS);
    }

    public UploadRecord markInProgress(String executionId, CandidateFile file, MachineMetadata metadata, String fingerprint) {
        UploadRecord record = repository.findByFingerprint(fingerprint)
                .map(existing -> {
                    // Reusing the row from a prior IN_PROGRESS/FAILED attempt for this file.
                    existing.setExecutionId(executionId);
                    existing.setStatus(UploadRecord.UploadStatus.IN_PROGRESS);
                    existing.setFailureReason(null);
                    existing.setCompletedAt(null);
                    existing.setCreatedAt(Instant.now());
                    return existing;
                })
                .orElseGet(() -> UploadRecord.builder()
                        .executionId(executionId)
                        .fingerprint(fingerprint)
                        .fileName(file.getFileName())
                        .fileSizeBytes(file.getSizeBytes())
                        .fileLastModified(file.getLastModified())
                        .machineId(metadata.getMachineId())
                        .status(UploadRecord.UploadStatus.IN_PROGRESS)
                        .createdAt(Instant.now())
                        .build());
        return repository.save(record);
    }

    public void markSuccess(UploadRecord record, String objectKey) {
        record.setStatus(UploadRecord.UploadStatus.SUCCESS);
        record.setS3ObjectKey(objectKey);
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }

    public void markFailed(UploadRecord record, String reason) {
        record.setStatus(UploadRecord.UploadStatus.FAILED);
        record.setFailureReason(truncate(reason));
        record.setCompletedAt(Instant.now());
        repository.save(record);
    }

    private String truncate(String reason) {
        if (reason == null) return null;
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }
}
