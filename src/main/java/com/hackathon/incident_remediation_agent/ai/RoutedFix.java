package com.hackathon.incident_remediation_agent.ai;

import java.util.Map;

/**
 * A fix proposal, the repository it applies to, and the file content the model was given.
 *
 * <p>The repository is kept outside {@link FixProposal} on purpose: the proposal is the model's
 * output contract, while the repository is a routing decision the caller has already validated
 * against the allowlist.
 *
 * <p>{@code originalFiles} is carried through so the gates can compare what the model was shown
 * against what it returned. Without a checkout there is no other way to notice a rewrite that
 * quietly dropped half a file.
 */
public record RoutedFix(
    String repositoryName,
    FixProposal proposal,
    Map<String, String> originalFiles
) {}
