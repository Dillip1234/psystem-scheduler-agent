package com.psystem.exception;

/** Thrown internally to short-circuit a scheduler run when no ZIP/RAR files are present. Not an error. */
public class NoCandidateFileException extends AgentException {
    public NoCandidateFileException(String message) {
        super(message);
    }
}
