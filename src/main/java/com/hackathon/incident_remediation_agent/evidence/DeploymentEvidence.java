package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.time.Instant;

public record DeploymentEvidence(String version, String commitSha, URI commitUrl, Instant deployedAt) {}
