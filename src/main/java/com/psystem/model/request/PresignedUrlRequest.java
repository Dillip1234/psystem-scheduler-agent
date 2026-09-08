package com.psystem.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * Payload sent to the backend to obtain a pre-signed S3 PUT URL.
 * Mirrors the contract documented in README.md section "API Contracts".
 */
@Value
@Builder
public class PresignedUrlRequest {

    @JsonProperty("machineId")
    String machineId;

    @JsonProperty("customerPrefix")
    String customerPrefix;

    @JsonProperty("location")
    String location;

    @JsonProperty("destinationPath")
    String destinationPath;

    @JsonProperty("fileName")
    String fileName;

    @JsonProperty("fileSize")
    long fileSize;

    @JsonProperty("fileType")
    String fileType;

    @JsonProperty("executionId")
    String executionId;
}
