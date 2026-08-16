package com.hackathon.incident_remediation_agent.workflow;

import com.hackathon.incident_remediation_agent.incident.IncidentRun;

@FunctionalInterface
public interface IncidentWorkflow {

    /**
     * Accepts an already-deduplicated run. Implementations must perform no synchronous external
     * work, so the webhook can return 202 immediately.
     */
    void start(IncidentRun run);
}
