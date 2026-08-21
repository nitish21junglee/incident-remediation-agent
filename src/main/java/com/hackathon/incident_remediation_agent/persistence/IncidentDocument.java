package com.hackathon.incident_remediation_agent.persistence;

import java.net.URI;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.evidence.RepositoryChange;
import com.hackathon.incident_remediation_agent.git.DraftPullRequest;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;

/**
 * One document per incident, upserted as the workflow progresses. {@code status} mirrors
 * {@link WorkflowStage} so the frontend can poll this collection instead of the in-memory
 * {@code IncidentRunStore}.
 *
 * <p>{@code splunkLogs} stays {@code null} until a real Splunk collector lands; nothing here
 * writes to it yet. {@code signalFxExports} holds the {@link MetricEvidence} for the run, which
 * carries both the aggregates the classifier used and, per SignalFlow program, the raw points as
 * SignalFx returned them.
 *
 * <p>{@code lastPullRequest} records what was last merged to the base branch when this incident
 * fired, with the time it merged, so the interval between a release and an incident is readable
 * from the stored document rather than reconstructed later.
 */
@Document(collection = "incidents")
public record IncidentDocument(
    @Id String incidentId,
    String jiraKey,
    URI jiraUrl,
    LogEvidence splunkLogs,
    MetricEvidence signalFxExports,
    RepositoryChange.PullRequest lastPullRequest,
    DraftPullRequest revertPullRequest,
    FixProposal aiOutput,
    Instant timestamp,
    WorkflowStage status
) {

    public static IncidentDocument received(String incidentId) {
        return new IncidentDocument(incidentId, null, null, null, null, null, null, null,
            Instant.now(), WorkflowStage.RECEIVED);
    }

    public IncidentDocument jiraCreated(String jiraKey, URI jiraUrl) {
        return new IncidentDocument(this.incidentId, jiraKey, jiraUrl, this.splunkLogs,
            this.signalFxExports, this.lastPullRequest, this.revertPullRequest, this.aiOutput,
            Instant.now(), WorkflowStage.JIRA_CREATED);
    }

    public IncidentDocument signalFxExports(MetricEvidence signalFxExports) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            signalFxExports, this.lastPullRequest, this.revertPullRequest, this.aiOutput,
            Instant.now(), this.status);
    }

    public IncidentDocument lastPullRequest(RepositoryChange.PullRequest lastPullRequest) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, lastPullRequest, this.revertPullRequest, this.aiOutput,
            Instant.now(), this.status);
    }

    public IncidentDocument revertPullRequest(DraftPullRequest revertPullRequest) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, this.lastPullRequest, revertPullRequest, this.aiOutput,
            Instant.now(), this.status);
    }

    public IncidentDocument aiOutput(FixProposal aiOutput) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, this.lastPullRequest, this.revertPullRequest, aiOutput,
            Instant.now(), this.status);
    }

    public IncidentDocument status(WorkflowStage status) {
        return new IncidentDocument(this.incidentId, this.jiraKey, this.jiraUrl, this.splunkLogs,
            this.signalFxExports, this.lastPullRequest, this.revertPullRequest, this.aiOutput,
            Instant.now(), status);
    }
}
