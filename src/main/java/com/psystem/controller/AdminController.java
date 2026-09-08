package com.psystem.controller;

import com.psystem.scheduler.BackupScheduler;
import com.psystem.scheduler.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal operational endpoint intended to be reachable only from localhost on the customer
 * machine (bind address / firewall configuration is an operator responsibility - see README
 * "Deployment" section). Lets an operator confirm the agent is alive and, if needed, trigger
 * an out-of-band run without waiting for the next cron tick.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final BackupScheduler backupScheduler;
    private final SchedulerLockService lockService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "executionInProgress", lockService.isRunning()
        ));
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerNow() {
        if (lockService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of("message", "An execution is already in progress"));
        }
        // Run asynchronously so the HTTP call returns immediately for a potentially long upload.
        Executors.newSingleThreadExecutor().submit(backupScheduler::runGuarded);
        return ResponseEntity.accepted().body(Map.of("message", "Execution triggered"));
    }
}
