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

    public IncidentRun jiraCreated(String jiraKey) {
        return at(WorkflowStage.JIRA_CREATED, "jira ticket created").withJiraKey(jiraKey);
    }

    public IncidentRun collectingContext() {
        return at(WorkflowStage.COLLECTING_CONTEXT, "collecting evidence");
    }

    public IncidentRun contextPublished(String commentId, String evidenceVersion) {
        return new IncidentRun(this.alert, WorkflowStage.JIRA_CONTEXT_PUBLISHED, this.jiraKey,
            commentId, evidenceVersion, this.draftPrUrl, "evidence published to jira");
    }

    public IncidentRun investigating() {
        return at(WorkflowStage.AI_INVESTIGATING, "asking the model for a fix");
    }

    public IncidentRun aiSkipped(String reason) {
        return at(WorkflowStage.AI_SKIPPED, reason);
    }

    public IncidentRun validating() {
        return at(WorkflowStage.VALIDATING, "checking the proposed change");
    }

    public IncidentRun draftPrCreated(URI draftPrUrl) {
        return new IncidentRun(this.alert, WorkflowStage.DRAFT_PR_CREATED, this.jiraKey,
            this.jiraContextCommentId, this.evidenceVersion, draftPrUrl, "draft pull request created");
    }

    public IncidentRun completed(String message) {
        return at(WorkflowStage.COMPLETED, message);
    }

    public IncidentRun failed(String message) {
        return at(WorkflowStage.FAILED, message);
    }

    private IncidentRun at(WorkflowStage stage, String message) {
        return new IncidentRun(this.alert, stage, this.jiraKey, this.jiraContextCommentId,
            this.evidenceVersion, this.draftPrUrl, message);
    }

    private IncidentRun withJiraKey(String jiraKey) {
        return new IncidentRun(this.alert, this.stage, jiraKey, this.jiraContextCommentId,
            this.evidenceVersion, this.draftPrUrl, this.message);
    }
}
