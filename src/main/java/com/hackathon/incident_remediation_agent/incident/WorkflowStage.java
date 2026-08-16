package com.hackathon.incident_remediation_agent.incident;

public enum WorkflowStage {
    RECEIVED,
    JIRA_CREATED,
    COLLECTING_CONTEXT,
    JIRA_CONTEXT_PUBLISHED,
    AI_SKIPPED,
    AI_INVESTIGATING,
    VALIDATING,
    DRAFT_PR_CREATED,
    COMPLETED,
    FAILED
}
