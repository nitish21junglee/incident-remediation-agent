package com.hackathon.incident_remediation_agent.evidence;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * Decides whether an incident looks like a bug in our code, a problem underneath it, or neither.
 *
 * <p>Only {@code application_error} lets the AI path run, so this is the gate that stops the agent
 * proposing code changes for someone else's outage. Every path that is not clearly a code fault
 * returns {@code unknown}, which means "investigate, do not patch".
 */
@Component
public class EvidenceClassifier {

    public static final String APPLICATION_ERROR = "application_error";
    public static final String INFRASTRUCTURE_OR_DEPENDENCY = "infrastructure_or_dependency";
    public static final String UNKNOWN = "unknown";

    /** An error rate has to rise by more than this factor to count as a regression. */
    private static final double ERROR_RATE_RISE_FACTOR = 1.2;

    /** How close a release has to be to the incident to be treated as a suspect. */
    private static final Duration RECENT_DEPLOYMENT = Duration.ofHours(2);

    /**
     * Transport and dependency failures. These say the process could not reach something, which is
     * not fixed by editing our own logic.
     */
    private static final List<String> INFRASTRUCTURE_MARKERS = List.of(
        "connectexception", "sockettimeout", "unknownhost", "connection refused",
        "connection reset", "no route to host", "broken pipe", "too many open files",
        "outofmemoryerror", "disk", "502 bad gateway", "503 service unavailable",
        "504 gateway timeout", "could not get a resource from the pool", "pool exhausted");

    /** Faults in our own logic, the kind a source change can actually fix. */
    private static final List<String> APPLICATION_MARKERS = List.of(
        "nullpointerexception", "indexoutofbounds", "illegalargumentexception",
        "illegalstateexception", "classcastexception", "numberformatexception",
        "arithmeticexception", "arrayindexoutofbounds", "unsupportedoperationexception",
        "jsonprocessingexception", "mismatchedinputexception", "constraintviolation");

    public String classify(IncidentAlert alert, LogEvidence logs, MetricEvidence metrics,
        DeploymentEvidence deployment) {

        if (logs == null || isBlank(logs.topError())) {
            return UNKNOWN;
        }
        if (metrics == null || noMetricSignal(metrics)) {
            // The collector zeroes metrics when SignalFx is unreachable. Without them there is no
            // evidence a regression happened at all, so this must not reach the AI path.
            return UNKNOWN;
        }

        String error = logs.topError().toLowerCase(Locale.ROOT);
        if (containsAny(error, INFRASTRUCTURE_MARKERS)) {
            return INFRASTRUCTURE_OR_DEPENDENCY;
        }

        boolean errorRateRose = errorRateRose(metrics);
        if (metrics.latencyChanged() && !errorRateRose) {
            // Slower but not failing more is saturation or a slow dependency, not a logic bug.
            return INFRASTRUCTURE_OR_DEPENDENCY;
        }
        if (!errorRateRose) {
            return UNKNOWN;
        }
        if (containsAny(error, APPLICATION_MARKERS) || deployedJustBefore(alert, deployment)) {
            return APPLICATION_ERROR;
        }
        return UNKNOWN;
    }

    private static boolean noMetricSignal(MetricEvidence metrics) {
        return metrics.errorRateBefore() == 0
            && metrics.errorRateDuring() == 0
            && !metrics.latencyChanged();
    }

    private static boolean errorRateRose(MetricEvidence metrics) {
        return metrics.errorRateDuring() > 0
            && metrics.errorRateDuring() > metrics.errorRateBefore() * ERROR_RATE_RISE_FACTOR;
    }

    private static boolean deployedJustBefore(IncidentAlert alert, DeploymentEvidence deployment) {
        if (deployment == null || deployment.deployedAt() == null || alert.triggeredAt() == null) {
            return false;
        }
        if (deployment.deployedAt().isAfter(alert.triggeredAt())) {
            return false;
        }
        return Duration.between(deployment.deployedAt(), alert.triggeredAt())
            .compareTo(RECENT_DEPLOYMENT) <= 0;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        return needles.stream().anyMatch(haystack::contains);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
