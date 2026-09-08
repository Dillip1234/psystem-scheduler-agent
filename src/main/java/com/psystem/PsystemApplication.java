package com.psystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Psystem Scheduler Agent.
 * <p>
 * This agent runs on a customer/local PC. On a configurable cron schedule it scans a
 * configured local directory, selects the single largest ZIP/RAR file, requests a
 * pre-signed S3 upload URL from a backend API, and streams the file directly to S3.
 * <p>
 * ZIP/RAR content is never extracted or processed by this application - that
 * responsibility belongs to a downstream Python AWS Lambda triggered by the S3
 * {@code ObjectCreated} event.
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class PsystemApplication {

    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(PsystemApplication.class, args);
    }
}
