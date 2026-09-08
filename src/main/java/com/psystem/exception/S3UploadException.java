package com.psystem.exception;

/** Thrown when the direct PUT upload to S3 fails after all configured retries, including expired-URL cases. */
public class S3UploadException extends AgentException {
    public S3UploadException(String message) {
        super(message);
    }

    public S3UploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
