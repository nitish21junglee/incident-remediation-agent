package com.hackathon.incident_remediation_agent.git;

import java.util.List;

public record WorkspaceResult(
    String branch,
    String commitSha,
    String validationOutput,
    List<String> changedFiles
) {}
