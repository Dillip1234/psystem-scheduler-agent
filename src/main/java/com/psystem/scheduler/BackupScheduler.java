package com.psystem.scheduler;

import com.psystem.batch.BackupWorkflow;
import com.psystem.config.AgentProperties;
import com.psystem.exception.DirectoryUnavailableException;
import com.psystem.exception.NoCandidateFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cron-triggered entry point. The cron expression itself is read from configuration
 * ({@code psystem.scheduler.cron}) via a property placeholder, so schedule changes never
 * require a code change or rebuild - only a configuration update and restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupScheduler {

    private final BackupWorkflow backupWorkflow;
    private final SchedulerLockService lockService;
    private final AgentProperties properties;

    @Scheduled(cron = "${psystem.scheduler.cron}")
    public void triggerScheduledExecution() {
        if (!properties.getScheduler().isEnabled()) {
            log.debug("Scheduler is disabled via psystem.scheduler.enabled=false - skipping this trigger.");
            return;
        }
        runGuarded();
    }

    /** Exposed for the manual-trigger actuator/admin endpoint and for tests. */
    public void runGuarded() {
        if (properties.getScheduler().isPreventOverlap() && !lockService.tryAcquire()) {
            log.warn("Previous scheduler execution is still in progress - skipping this trigger " +
                    "to avoid overlapping/concurrent processing of the same directory.");
            return;
        }

        String executionId = UUID.randomUUID().toString().substring(0, 8);
        try {
            backupWorkflow.run(executionId);
        } catch (NoCandidateFileException e) {
            // Not an error: expected outcome when the directory has nothing new to upload.
            log.info("Execution {} completed with no action: {}", executionId, e.getMessage());
        } catch (DirectoryUnavailableException e) {
            log.error("Execution {} FAILED - source directory unavailable: {}", executionId, e.getMessage());
        } catch (Exception e) {
            log.error("Execution {} FAILED with an unexpected error: {}", executionId, e.getMessage(), e);
        } finally {
            if (properties.getScheduler().isPreventOverlap()) {
                lockService.release();
            }
        }
    }

    /**
     * Optional convenience: log the resolved schedule at startup so operators can confirm the
     * configured cron expression without having to decode it manually.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logScheduleOnStartup() {
        log.info("Psystem Scheduler Agent ready. cron='{}' enabled={} sourceDirectory='{}'",
                properties.getScheduler().getCron(),
                properties.getScheduler().isEnabled(),
                properties.getFile().getSourceDirectory());
    }
}
