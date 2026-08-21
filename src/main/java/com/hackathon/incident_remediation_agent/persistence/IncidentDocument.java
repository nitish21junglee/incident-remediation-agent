package com.hackathon.incident_remediation_agent.persistence;

import java.net.URI;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;

/**
 * One document per incident, upserted as the workflow progresses. {@code status} mirrors
 * {@link WorkflowStage} so the frontend can poll this collection instead of the in-memory
 * {@code IncidentRunStore}.
 *
 * <p>{@code splunkLogs} stays {@code null} until a real Splunk collector lands; nothing here
 * writes to it yet. {@code signalFxExports} holds the aggregated {@link MetricEvidence} already
 * computed for the run, not the raw per-query SignalFlow JSON.
 */
@Document(collection = "incidents")
public record IncidentDocument(
    @Id String incidentId,
    String jiraKey,
    URI jiraUrl,
    LogEvidence splunkLogs,
    MetricEvidence signalFxExports,
    FixProposal aiOutput,
    Instant timestamp,
    WorkflowStage status
) {

    public static IncidentDocument received(String incidentId) {
        return new IncidentDocument(incidentId, null, null, null, null, null, Instant.now(), WorkflowStage.RECEIVED);
    }

    public IncidentDocument jiraCreated(String jiraKey, URI jiraUrl) {
        return new IncidentDocument(this.incidentId, jiraKey, jiraUrl, this.splunkLogs,
            this.signalFxExports, this.aiOutput, Instant.now(), WorkflowStage.JIRA_CREATED);
    }

    public IncidentDocument signalFxExports(MetricEvidence signalFxExports) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            signalFxExports, this.aiOutput, Instant.now(), this.status);
    }

    public IncidentDocument aiOutput(FixProposal aiOutput) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, aiOutput, Instant.now(), this.status);
    }

    public IncidentDocument status(WorkflowStage status) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, this.aiOutput, Instant.now(), status);
    }
}
