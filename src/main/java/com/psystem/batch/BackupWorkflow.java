package com.psystem.batch;

import com.psystem.exception.NoCandidateFileException;
import com.psystem.exception.S3UploadException;
import com.psystem.model.domain.CandidateFile;
import com.psystem.model.domain.MachineMetadata;
import com.psystem.model.domain.UploadRecord;
import com.psystem.model.response.PresignedUrlResponse;
import com.psystem.service.file.FileScannerService;
import com.psystem.service.file.FileStabilityService;
import com.psystem.service.idempotency.IdempotencyService;
import com.psystem.service.metadata.MachineMetadataService;
import com.psystem.service.notification.NotificationService;
import com.psystem.service.presignedurl.PresignedUrlClient;
import com.psystem.service.upload.S3UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Orchestrates a single scheduler execution as a sequence of discrete, individually
 * testable steps - mirroring the logical Job/Step breakdown from the design spec, but
 * implemented as plain method calls rather than a Spring Batch Job.
 * <p>
 * <b>Why not Spring Batch:</b> Spring Batch earns its complexity (JobRepository, chunk
 * processing, step restart metadata, partitioning) when a job processes many items per run,
 * needs chunked commit/rollback semantics, or benefits from Batch's built-in restart-from-
 * failed-step machinery. This workflow selects and uploads exactly one file per execution -
 * there is no chunking, no multi-item reader/processor/writer pattern, and nothing to
 * partition. Introducing Spring Batch here would add a JobRepository database, Batch's own
 * metadata tables, and a job-configuration layer purely to run six sequential method calls.
 * <p>
 * Instead:
 * <ul>
 *   <li>{@code @Scheduled} (see {@link com.psystem.scheduler.BackupScheduler}) provides the
 *       cron trigger Batch would otherwise need a separate launcher for.</li>
 *   <li>{@link com.psystem.service.idempotency.IdempotencyService} provides the restart-safety
 *       and duplicate-prevention that Batch's JobRepository would otherwise provide, scoped to
 *       exactly what this workflow needs.</li>
 *   <li>Each step below is its own Spring-managed service, independently unit-testable,
 *       giving the same separation-of-concerns benefit as Batch's Tasklet/Step model without
 *       the operational overhead.</li>
 * </ul>
 * If a future requirement introduces multi-file batch uploads with chunked processing,
 * revisit this decision - Spring Batch would then be justified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupWorkflow {

    private final FileScannerService fileScannerService;
    private final FileStabilityService fileStabilityService;
    private final MachineMetadataService machineMetadataService;
    private final PresignedUrlClient presignedUrlClient;
    private final S3UploadService s3UploadService;
    private final IdempotencyService idempotencyService;
    private final NotificationService notificationService;

    public WorkflowResult run(String executionId) {
        log.info("Scheduler started. executionId={}", executionId);

        // Step 1 + 2: Scan directory and find the largest ZIP/RAR file created today.
        Optional<CandidateFile> selected = fileScannerService.findLargest();
        if (selected.isEmpty()) {
            log.info("No eligible ZIP/RAR file (created today) found. executionId={} - " +
                    "sending file-not-found notification and completing as no-op.", executionId);
            notificationService.notifyFileNotFound(executionId);
            throw new NoCandidateFileException("No eligible ZIP/RAR file in source directory");
        }
        CandidateFile file = selected.get();
        log.info("Selected file. executionId={} name={} sizeBytes={}",
                executionId, file.getFileName(), file.getSizeBytes());

        // Step 3: Collect machine metadata.
        MachineMetadata metadata = machineMetadataService.resolve();
        log.info("Machine metadata resolved. executionId={} machineId={} location={}",
                executionId, metadata.getMachineId(), metadata.getLocation());

        // Idempotency guard, before we do any stability wait or network calls.
        String fingerprint = idempotencyService.fingerprint(file, metadata);
        if (idempotencyService.isAlreadyUploaded(fingerprint)) {
            log.info("File already uploaded successfully in a previous execution - skipping. " +
                    "executionId={} fingerprint={}", executionId, fingerprint);
            notificationService.notifyDuplicateSkipped(executionId, file, fingerprint);
            return WorkflowResult.skipped(file);
        }

        // Ensure the file is not still being written to before we commit to uploading it.
        fileStabilityService.verifyStable(file);

        UploadRecord record = idempotencyService.markInProgress(executionId, file, metadata, fingerprint);

        try {
            // Step 4: Request pre-signed URL.
            PresignedUrlResponse presignedUrl = presignedUrlClient.requestPresignedUrl(file, metadata, executionId);

            // Step 5: Upload file to S3.
            s3UploadService.upload(file, presignedUrl, metadata);

            // Step 6: Record upload result.
            idempotencyService.markSuccess(record, presignedUrl.getObjectKey());
            log.info("Scheduler completed successfully. executionId={} objectKey={}",
                    executionId, presignedUrl.getObjectKey());
            notificationService.notifyUploadSuccess(executionId, file, presignedUrl.getObjectKey());

            return WorkflowResult.success(file, presignedUrl.getObjectKey());

        } catch (IOException e) {
            idempotencyService.markFailed(record, e.getMessage());
            throw new S3UploadException("Upload failed due to an I/O error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            idempotencyService.markFailed(record, e.getMessage());
            throw e;
        }
    }

    public record WorkflowResult(CandidateFile file, String objectKey, Status status) {
        enum Status {SUCCESS, SKIPPED_DUPLICATE}

        static WorkflowResult success(CandidateFile file, String objectKey) {
            return new WorkflowResult(file, objectKey, Status.SUCCESS);
        }

        static WorkflowResult skipped(CandidateFile file) {
            return new WorkflowResult(file, null, Status.SKIPPED_DUPLICATE);
        }
    }
}
