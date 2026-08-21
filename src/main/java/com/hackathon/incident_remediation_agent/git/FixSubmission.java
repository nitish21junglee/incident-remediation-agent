package com.hackathon.incident_remediation_agent.git;

import java.util.List;

/**
 * What the agent did, or would have done, with a proposal that passed the gates.
 *
 * @param pullRequest the draft pull request, or {@code null} when {@code agent.github.push-enabled}
 *                    is false. A null value means nothing at all was written to GitHub, and
 *                    {@code commitSha} is null too while {@code branch} is the name that would have
 *                    been used.
 */
public record FixSubmission(
    String branch,
    String commitSha,
    List<String> changedFiles,
    DraftPullRequest pullRequest
) {

    public boolean pushed() {
        return this.pullRequest != null;
    }
}
