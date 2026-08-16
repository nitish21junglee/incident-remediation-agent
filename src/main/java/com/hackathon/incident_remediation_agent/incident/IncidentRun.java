package com.hackathon.incident_remediation_agent.incident;

import java.net.URI;

public record IncidentRun(
    IncidentAlert alert,
    WorkflowStage stage,
    String jiraKey,
    String jiraContextCommentId,
    String evidenceVersion,
    URI draftPrUrl,
    String message
) {

    static IncidentRun received(IncidentAlert alert) {
        return new IncidentRun(alert, WorkflowStage.RECEIVED, null, null, null, null, "accepted");
    }
}
