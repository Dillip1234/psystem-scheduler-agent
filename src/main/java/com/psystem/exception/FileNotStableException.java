package com.psystem.exception;

/** Thrown when the selected file's size keeps changing during the stability check, or it disappears. */
public class FileNotStableException extends AgentException {
    public FileNotStableException(String message) {
        super(message);
    }
}
