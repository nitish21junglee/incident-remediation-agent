package com.hackathon.incident_remediation_agent.ai;

import java.util.Map;

/**
 * The model's answer: whether it believes there is a code fix, why, and the full new content of
 * every file it wants to change.
 *
 * <p>Whole files rather than a unified diff. A model's diff hunk headers are frequently wrong in
 * ways that have nothing to do with whether the fix is correct, and there is no checkout to run
 * {@code git apply} against — the commit is built from file contents through the GitHub API.
 *
 * @param files repository-relative path to the complete replacement content
 */
public record FixProposal(
    boolean probableFix,
    String hypothesis,
    String summary,
    Map<String, String> files
) {}
