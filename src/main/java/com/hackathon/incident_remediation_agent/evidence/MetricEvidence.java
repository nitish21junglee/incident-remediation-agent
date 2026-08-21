package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.util.List;

/**
 * @param exports one entry per SignalFlow program run for this incident, in the order they ran.
 *                The first three fields are derived from a subset of these and are what
 *                {@code EvidenceClassifier} decides on; the exports themselves are context.
 */
public record MetricEvidence(
    double errorRateBefore,
    double errorRateDuring,
    boolean latencyChanged,
    URI dashboardUrl,
    List<SignalFxExport> exports
) {}
