package com.psystem.exception;

/** Thrown when the configured source directory does not exist, is not a directory, or is not readable. */
public class DirectoryUnavailableException extends AgentException {
    public DirectoryUnavailableException(String message) {
        super(message);
    }

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
