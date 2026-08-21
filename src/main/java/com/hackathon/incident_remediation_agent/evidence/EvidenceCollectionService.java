package com.hackathon.incident_remediation_agent.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.hackathon.incident_remediation_agent.evidence.signalfx.SignalFxCollector;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gathers logs, metrics and the current deployment, classifies them, and stamps the result with a
 * version.
 *
 * <p>{@code evidenceVersion} is a SHA-256 over the evidence in a fixed field order. It travels into
 * Jira and the pull request, and is re-checked immediately before anything is written to GitHub, so
 * a fix built on evidence that has since been superseded cannot land. The Jira ticket is excluded
 * from the digest: which ticket holds the evidence is not itself evidence.
 */
@Service
public class EvidenceCollectionService {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SplunkCollector splunk;
    private final SignalFxCollector signalfx;
    private final DeploymentCollector deployments;
    private final GitHubChangeCollector changes;
    private final RepositoryResolver repositories;
    private final EvidenceClassifier classifier;

    EvidenceCollectionService(SplunkCollector splunk, SignalFxCollector signalfx,
        DeploymentCollector deployments, GitHubChangeCollector changes,
        RepositoryResolver repositories, EvidenceClassifier classifier) {
        this.splunk = splunk;
        this.signalfx = signalfx;
        this.deployments = deployments;
        this.changes = changes;
        this.repositories = repositories;
        this.classifier = classifier;
    }

    public EvidencePack collect(IncidentAlert alert, JiraTicket ticket) {
        LogEvidence logs = this.splunk.collect(alert);
        MetricEvidence metrics = this.signalfx.collect(alert);
        DeploymentEvidence deployment = this.deployments.collect(alert);
        String classification = this.classifier.classify(alert, logs, metrics, deployment);

        return new EvidencePack(alert, ticket, logs, metrics, deployment, change(alert),
            classification, version(alert, logs, metrics, deployment, classification));
    }

    /**
     * The repository is resolved from the alert's service here rather than waiting for the AI
     * router, so the last merged pull request is recorded for every incident, including the ones
     * that never reach a model.
     */
    private RepositoryChange change(IncidentAlert alert) {
        return this.repositories.resolve(alert).map(this.changes::collect).orElse(null);
    }

    private static String version(IncidentAlert alert, LogEvidence logs, MetricEvidence metrics,
        DeploymentEvidence deployment, String classification) {

        ObjectNode canonical = NODES.objectNode();
        canonical.put("incidentId", alert.incidentId());
        canonical.put("serviceId", alert.serviceId());
        canonical.put("triggeredAt", String.valueOf(alert.triggeredAt()));

        ObjectNode logNode = canonical.putObject("logs");
        if (logs != null) {
            logNode.put("errorCount", logs.errorCount());
            logNode.put("topError", logs.topError());
            ArrayNode samples = logNode.putArray("samples");
            if (logs.samples() != null) {
                logs.samples().forEach(samples::add);
            }
        }

        ObjectNode metricNode = canonical.putObject("metrics");
        if (metrics != null) {
            metricNode.put("errorRateBefore", metrics.errorRateBefore());
            metricNode.put("errorRateDuring", metrics.errorRateDuring());
            metricNode.put("latencyChanged", metrics.latencyChanged());
        }

        ObjectNode deploymentNode = canonical.putObject("deployment");
        if (deployment != null) {
            deploymentNode.put("version", deployment.version());
            deploymentNode.put("commitSha", deployment.commitSha());
            deploymentNode.put("deployedAt", String.valueOf(deployment.deployedAt()));
        }

        canonical.put("classification", classification);
        return sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the platform", exception);
        }
    }
}
