package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.util.List;

public record LogEvidence(long errorCount, String topError, List<String> samples, URI sourceUrl) {}
