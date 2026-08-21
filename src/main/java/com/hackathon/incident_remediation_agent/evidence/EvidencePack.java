package com.hackathon.incident_remediation_agent.evidence;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

/**
 * @param classification one of {@code application_error}, {@code infrastructure_or_dependency},
 *                       or {@code unknown} as produced by {@code EvidenceClassifier}
 * @param change what last landed on the repository's base branch, or null when it could not be
 *               read. Deliberately outside {@code evidenceVersion}: the digest exists to detect
 *               the evidence moving under a fix, and the repository head is not evidence about
 *               the incident in that sense.
 * @param evidenceVersion SHA-256 hex digest of the canonical JSON of the other fields
 */
public record EvidencePack(
    IncidentAlert alert,
    JiraTicket ticket,
    LogEvidence logs,
    MetricEvidence metrics,
    DeploymentEvidence deployment,
    RepositoryChange change,
    String classification,
    String evidenceVersion
) {}
