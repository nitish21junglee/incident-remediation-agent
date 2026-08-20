package com.hackathon.incident_remediation_agent.ai;

/**
 * A fix proposal together with the repository it applies to.
 *
 * <p>The repository is kept outside {@link FixProposal} on purpose: the proposal is the model's
 * output contract, while the repository is a routing decision the caller has already validated
 * against the allowlist.
 */
public record RoutedFix(String repositoryName, FixProposal proposal) {}
