package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;

public record MetricEvidence(
    double errorRateBefore,
    double errorRateDuring,
    boolean latencyChanged,
    URI dashboardUrl
) {}
