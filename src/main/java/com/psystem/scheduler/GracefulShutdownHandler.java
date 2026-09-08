package com.psystem.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application shutdown (SIGTERM, service stop, etc.), waits briefly for an in-progress
 * upload to reach a natural checkpoint rather than being killed mid-stream. Because upload
 * progress is tracked via {@link com.psystem.model.domain.UploadRecord} (IN_PROGRESS ->
 * SUCCESS/FAILED), an interrupted upload simply leaves an IN_PROGRESS record behind; the next
 * scheduled execution treats the file as not-yet-successfully-uploaded and retries it, so a
 * hard kill is safe even if this grace period is insufficient - it never corrupts state or
 * silently loses/duplicates an upload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracefulShutdownHandler {

    private static final long MAX_WAIT_MS = 30_000;
    private static final long POLL_INTERVAL_MS = 500;

    private final SchedulerLockService lockService;

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        if (!lockService.isRunning()) {
            log.info("Shutdown requested - no execution in progress, exiting cleanly.");
            return;
        }

        log.info("Shutdown requested while an execution is in progress. Waiting up to {}ms " +
                "for it to reach a safe checkpoint...", MAX_WAIT_MS);

        long waited = 0;
        while (lockService.isRunning() && waited < MAX_WAIT_MS) {
            sleep(POLL_INTERVAL_MS);
            waited += POLL_INTERVAL_MS;
        }

        if (lockService.isRunning()) {
            log.warn("Execution still in progress after {}ms grace period. Proceeding with shutdown - " +
                    "the in-progress upload record will be retried on next startup/schedule.", MAX_WAIT_MS);
        } else {
            log.info("Execution finished cleanly within the shutdown grace period.");
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
