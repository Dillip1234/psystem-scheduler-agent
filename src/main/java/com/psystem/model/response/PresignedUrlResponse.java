package com.psystem.model.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PresignedUrlResponse {

    @JsonProperty("uploadUrl")
    private String uploadUrl;

    @JsonProperty("bucket")
    private String bucket;

    @JsonProperty("objectKey")
    private String objectKey;

    @JsonProperty("expiresIn")
    private long expiresIn;

    /**
     * Optional map of headers (e.g. x-amz-meta-* entries, Content-Type) that the caller
     * MUST include on the PUT request for the pre-signed URL to validate against the
     * signature. If the backend pre-signs specific metadata headers, they are returned here.
     */
    @JsonProperty("requiredHeaders")
    private Map<String, String> requiredHeaders;
}
