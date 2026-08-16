package com.hackathon.incident_remediation_agent.ai;

public record FixProposal(boolean probableFix, String hypothesis, String summary, String unifiedDiff) {}
