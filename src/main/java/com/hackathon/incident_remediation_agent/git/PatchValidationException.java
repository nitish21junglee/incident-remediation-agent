package com.hackathon.incident_remediation_agent.git;

/**
 * Signals an expected no-PR outcome: patch application failure, protected path, too many changed
 * files, a failing validation command, or stale evidence. The workflow treats this as a completed
 * investigation, not an infrastructure failure.
 */
public class PatchValidationException extends RuntimeException {

    public PatchValidationException(String message) {
        super(message);
    }

    public PatchValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
