package com.hackathon.incident_remediation_agent.git;

public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    public boolean succeeded() {
        return this.exitCode == 0 && !this.timedOut;
    }
}
