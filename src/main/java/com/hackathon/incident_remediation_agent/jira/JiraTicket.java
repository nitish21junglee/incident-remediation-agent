package com.hackathon.incident_remediation_agent.jira;

import java.net.URI;

public record JiraTicket(String key, URI browseUrl) {}
