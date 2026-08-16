package com.hackathon.incident_remediation_agent.git;

import java.net.URI;

public record DraftPullRequest(long number, URI url) {}
