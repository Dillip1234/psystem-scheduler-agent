package com.psystem.service.presignedurl;

import com.psystem.config.AgentProperties;
import com.psystem.exception.PresignedUrlException;
import com.psystem.model.domain.CandidateFile;
import com.psystem.model.domain.MachineMetadata;
import com.psystem.model.request.PresignedUrlRequest;
import com.psystem.model.response.PresignedUrlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Client for the backend "request pre-signed S3 upload URL" API.
 * <p>
 * The target endpoint ({@code psystem.upload.presigned-url-api}) is resolved to an absolute
 * {@link URI} and passed explicitly on every request rather than relying on a client-level
 * {@code baseUrl}, so the endpoint being called is visible right here at the call site.
 * <p>
 * Retries with exponential backoff only on transient failures (5xx, connection/timeout
 * errors). 4xx responses (bad request, auth failure) are NOT retried since retrying an
 * invalid request will never succeed - those fail fast with a clear error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresignedUrlClient {

    private final RestClient presignedUrlRestClient;
    private final AgentProperties properties;

    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            maxAttemptsExpression = "#{${psystem.upload.max-retries} + 1}",
            backoff = @Backoff(
                    delayExpression = "#{${psystem.upload.initial-backoff-ms}}",
                    multiplierExpression = "#{${psystem.upload.backoff-multiplier}}"
            )
    )
    public PresignedUrlResponse requestPresignedUrl(CandidateFile file, MachineMetadata metadata, String executionId) {
        PresignedUrlRequest requestBody = PresignedUrlRequest.builder()
                .machineId(metadata.getMachineId())
                .customerPrefix(metadata.getCustomerPrefix())
                .location(metadata.getLocation())
                .destinationPath(metadata.getDestinationPath())
                .fileName(file.getFileName())
                .fileSize(file.getSizeBytes())
                .fileType(file.getFileType().name())
                .executionId(executionId)
                .build();

        URI endpoint = URI.create(properties.getUpload().getPresignedUrlApi());

        log.info("Requesting pre-signed URL. executionId={} fileName={} fileSize={} bytes endpoint={}",
                executionId, file.getFileName(), file.getSizeBytes(), endpoint);

        try {
            RestClient.RequestBodySpec request = presignedUrlRestClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json");

            String apiKey = properties.getUpload().getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }

            PresignedUrlResponse response = request
                    .body(requestBody)
                    .retrieve()
                    .body(PresignedUrlResponse.class);

            if (response == null || response.getUploadUrl() == null || response.getUploadUrl().isBlank()) {
                throw new PresignedUrlException("Pre-signed URL API returned an empty/invalid response");
            }

            log.info("Pre-signed URL received. executionId={} objectKey={} expiresIn={}s",
                    executionId, response.getObjectKey(), response.getExpiresIn());
            // Deliberately never log response.getUploadUrl() - it is a bearer-style credential.
            return response;

        } catch (HttpClientErrorException e) {
            // 4xx - not retried, fails fast.
            throw new PresignedUrlException(
                    "Pre-signed URL API rejected the request (HTTP " + e.getStatusCode() + "): " + safeBody(e), e);
        }
    }

    private String safeBody(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAsString();
        } catch (Exception ignored) {
            return "<unavailable>";
        }
    }
}
