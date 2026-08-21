package com.hackathon.incident_remediation_agent.evidence.signalfx;

/** Signals that a SignalFlow query could not be executed or its response could not be parsed. */
public class SignalFxQueryException extends RuntimeException {

    public SignalFxQueryException(String message) {
        super(message);
    }

    public SignalFxQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
