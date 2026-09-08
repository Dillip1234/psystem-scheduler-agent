package com.psystem.exception;

/** Thrown when a file that was selected during scanning no longer exists at upload time. */
public class FileVanishedException extends AgentException {
    public FileVanishedException(String message) {
        super(message);
    }
}
