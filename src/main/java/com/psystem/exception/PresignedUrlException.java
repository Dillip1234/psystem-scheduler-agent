package com.psystem.exception;

/** Thrown when the backend pre-signed URL API returns an error or is unreachable after retries. */
public class PresignedUrlException extends AgentException {
    public PresignedUrlException(String message) {
        super(message);
    }

    public PresignedUrlException(String message, Throwable cause) {
        super(message, cause);
    }
}
