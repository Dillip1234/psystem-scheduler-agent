package com.psystem.service.notification;

import com.psystem.config.AgentProperties;
import com.psystem.model.domain.CandidateFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Sends email alerts for every notable outcome of a scheduled backup/upload execution:
 * successful upload, skipped-as-duplicate, and failure. Recipients and the on/off switch for
 * each event type are entirely configuration-driven ({@code psystem.notification.*} in
 * application.yml, one or many Gmail/any-SMTP addresses) - no address is ever hard-coded.
 * <p>
 * Sending is always best-effort: any {@link MailException} is caught and logged rather than
 * propagated, so a broken mail server / bad credentials can never mask, replace, or crash the
 * outcome it was trying to report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final JavaMailSender mailSender;
    private final AgentProperties properties;

    /** Notifies all configured recipients that a file uploaded successfully. */
    public void notifyUploadSuccess(String executionId, CandidateFile file, String objectKey) {
        AgentProperties.Notification config = properties.getNotification();
        if (!config.isNotifyOnSuccess()) {
            log.debug("Success email notification is disabled (psystem.notification.notify-on-success=false) - " +
                    "skipping email for executionId={}", executionId);
            return;
        }

        String subject = String.format("Backup upload SUCCEEDED - %s (execution %s)",
                file.getFileName(), executionId);
        String body = String.format(
                "File uploaded successfully.%n%n" +
                        "Time:            %s%n" +
                        "Execution ID:    %s%n" +
                        "File name:       %s%n" +
                        "File size:       %d bytes%n" +
                        "Machine/location:%s%n" +
                        "S3 object key:   %s%n",
                TIMESTAMP_FORMAT.format(Instant.now()),
                executionId,
                file.getFileName(),
                file.getSizeBytes(),
                properties.getMachine().getLocation()
                //objectKey
        );

        send(config, subject, body, executionId, "success");
    }

    /** Notifies all configured recipients that a run was skipped - the file was already uploaded previously. */
    public void notifyDuplicateSkipped(String executionId, CandidateFile file, String fingerprint) {
        AgentProperties.Notification config = properties.getNotification();
        if (!config.isNotifyOnDuplicateSkipped()) {
            log.debug("Duplicate-skip email notification is disabled " +
                    "(psystem.notification.notify-on-duplicate-skipped=false) - skipping email for executionId={}",
                    executionId);
            return;
        }

        String subject = String.format("Backup upload SKIPPED (already uploaded) - %s (execution %s)",
                file.getFileName(), executionId);
        String body = String.format(
                "File already uploaded successfully in a previous execution - this run was skipped.%n%n" +
                        "Time:            %s%n" +
                        "Execution ID:    %s%n" +
                        "File name:       %s%n" +
                        "Fingerprint:     %s%n" +
                        "Machine/location:%s%n",
                TIMESTAMP_FORMAT.format(Instant.now()),
                executionId,
                file.getFileName(),
                fingerprint,
                properties.getMachine().getLocation()
        );

        send(config, subject, body, executionId, "duplicate-skip");
    }

    /**
     * Notifies all configured recipients that a scheduled execution failed.
     *
     * @param executionId short correlation id for this run, also present in the logs
     * @param failureStage a short human label for where the failure occurred
     *                     (e.g. "Directory scan", "Pre-signed URL request", "S3 upload")
     * @param error the exception that caused the failure
     */
    public void notifyUploadFailure(String executionId, String failureStage, Throwable error) {
        AgentProperties.Notification config = properties.getNotification();
        if (!config.isNotifyOnFailure()) {
            log.debug("Failure email notification is disabled (psystem.notification.notify-on-failure=false) - " +
                    "skipping email for executionId={}", executionId);
            return;
        }

        String subject = String.format("Backup upload FAILED - %s (execution %s)",
                properties.getMachine().getLocation(), executionId);
        String body = String.format(
                "The Psystem backup/upload agent could not complete its scheduled run.%n%n" +
                        "Time:            %s%n" +
                        "Execution ID:    %s%n" +
                        "Machine/location:%s%n" +
                        "Source directory:%s%n" +
                        "Failed stage:    %s%n" +
                        "Error type:      %s%n" +
                        "Error message:   %s%n%n" +
                        "This is an automated alert. Check the agent logs on the source machine " +
                        "for the full stack trace and detail.%n",
                TIMESTAMP_FORMAT.format(Instant.now()),
                executionId,
                properties.getMachine().getLocation(),
                properties.getFile().getSourceDirectory(),
                failureStage,
                error.getClass().getSimpleName(),
                String.valueOf(error.getMessage())
        );

        send(config, subject, body, executionId, "failure");
    }

    private void send(AgentProperties.Notification config, String subject, String body,
                       String executionId, String eventType) {
        if (!config.isEnabled()) {
            log.debug("Email notifications are disabled (psystem.notification.enabled=false) - " +
                    "skipping {} email for executionId={}", eventType, executionId);
            return;
        }

        if (config.getTo() == null || config.getTo().isEmpty()) {
            log.warn("A {} email would have been sent for executionId={} but no recipients are configured " +
                    "under psystem.notification.to - skipping. Configure one or more addresses in " +
                    "application.yml to receive these alerts.", eventType, executionId);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (config.getFrom() != null && !config.getFrom().isBlank()) {
                message.setFrom(config.getFrom());
            }
            message.setTo(config.getTo().toArray(new String[0]));
            message.setSubject(config.getSubjectPrefix() + " " + subject);
            message.setText(body);

            mailSender.send(message);
            log.info("{} notification email sent. executionId={} recipients={}",
                    eventType, executionId, config.getTo().size());
        } catch (MailException e) {
            // Never let a mail-delivery problem escalate into (or mask) the outcome it was
            // trying to report - just log it clearly so the operator can fix SMTP config separately.
            log.error("Failed to send {} notification email for executionId={}: {}",
                    eventType, executionId, e.getMessage(), e);
        }
    }
}
