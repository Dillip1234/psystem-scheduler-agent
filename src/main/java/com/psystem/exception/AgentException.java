package com.psystem.exception;

/** Base unchecked exception for all agent-specific failures. */
public class AgentException extends RuntimeException {
    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
