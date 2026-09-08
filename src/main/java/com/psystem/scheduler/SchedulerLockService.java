package com.psystem.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple in-process lock preventing a scheduled execution from starting while a previous one
 * is still running - relevant if a single upload takes longer than the configured cron
 * interval (e.g. a slow upload of a very large file). Since exactly one JVM instance of this
 * agent runs per customer machine, an in-memory flag is sufficient; no distributed lock is
 * required.
 */
@Slf4j
@Component
public class SchedulerLockService {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** @return true if the lock was acquired (i.e. no execution currently in progress). */
    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    public void release() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}
