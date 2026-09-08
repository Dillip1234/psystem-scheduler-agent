package com.psystem.service.upload;

import com.psystem.config.AgentProperties;
import com.psystem.exception.FileVanishedException;
import com.psystem.exception.S3UploadException;
import com.psystem.model.domain.CandidateFile;
import com.psystem.model.domain.MachineMetadata;
import com.psystem.model.response.PresignedUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;

/**
 * Streams the selected ZIP/RAR file directly to S3 using the pre-signed URL, without ever
 * loading the full file into JVM heap memory. Uses {@link HttpURLConnection} in chunked
 * streaming mode (fixed-length streaming, since the file size is known up front) so memory
 * usage stays proportional to the buffer size regardless of file size (500MB, multi-GB, etc).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3UploadService {

    private static final int PROGRESS_LOG_INTERVAL_BYTES = 50 * 1024 * 1024; // log every 50MB

    private final AgentProperties properties;

    /**
     * Retries only on transient network failures. A 403 (expired/invalid signature) is
     * intentionally NOT retried here - the caller (UploadOrchestrationService) is responsible
     * for detecting an expired URL, requesting a fresh one, and retrying the whole upload,
     * since resending against the same expired URL can never succeed.
     */
    @Retryable(
            retryFor = {IOException.class},
            maxAttemptsExpression = "#{${psystem.upload.max-retries} + 1}",
            backoff = @Backoff(
                    delayExpression = "#{${psystem.upload.initial-backoff-ms}}",
                    multiplierExpression = "#{${psystem.upload.backoff-multiplier}}"
            )
    )
    public void upload(CandidateFile file, PresignedUrlResponse presignedUrl, MachineMetadata metadata) throws IOException {
        if (!Files.exists(file.getPath())) {
            throw new FileVanishedException(
                    "Selected file vanished before upload could start: " + file.getFileName());
        }

        HttpURLConnection connection = null;
        long bytesSent = 0;
        long nextProgressLogAt = PROGRESS_LOG_INTERVAL_BYTES;

        try {
            URL url = URI.create(presignedUrl.getUploadUrl()).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setConnectTimeout(properties.getUpload().getConnectionTimeoutMs());
            connection.setReadTimeout(properties.getUpload().getReadTimeoutMs());
            connection.setFixedLengthStreamingMode(file.getSizeBytes());
            connection.setRequestProperty("Content-Type", "application/octet-stream");

            if (presignedUrl.getRequiredHeaders() != null) {
                for (Map.Entry<String, String> header : presignedUrl.getRequiredHeaders().entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            int bufferSize = properties.getUpload().getUploadBufferSizeBytes();
            byte[] buffer = new byte[bufferSize];

            log.info("Starting S3 upload. fileName={} sizeBytes={} objectKey={}",
                    file.getFileName(), file.getSizeBytes(), presignedUrl.getObjectKey());

            try (InputStream in = new BufferedInputStream(Files.newInputStream(file.getPath()), bufferSize);
                 OutputStream out = connection.getOutputStream()) {

                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    bytesSent += read;

                    if (bytesSent >= nextProgressLogAt) {
                        int percent = (int) ((bytesSent * 100) / file.getSizeBytes());
                        log.info("Upload progress: {}% ({}/{} bytes) fileName={}",
                                percent, bytesSent, file.getSizeBytes(), file.getFileName());
                        nextProgressLogAt += PROGRESS_LOG_INTERVAL_BYTES;
                    }
                }
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == 403) {
                throw new S3UploadException(
                        "S3 rejected the upload with 403 - the pre-signed URL is likely expired or invalid. " +
                        "objectKey=" + presignedUrl.getObjectKey());
            }
            if (responseCode < 200 || responseCode >= 300) {
                throw new S3UploadException(String.format(
                        "S3 upload failed with unexpected HTTP status %d for objectKey=%s",
                        responseCode, presignedUrl.getObjectKey()));
            }

            log.info("Upload successful. fileName={} objectKey={} bytesSent={}",
                    file.getFileName(), presignedUrl.getObjectKey(), bytesSent);

        } catch (IOException e) {
            log.error("S3 upload I/O failure after sending {}/{} bytes for fileName={}: {}",
                    bytesSent, file.getSizeBytes(), file.getFileName(), e.getMessage());
            throw e;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
