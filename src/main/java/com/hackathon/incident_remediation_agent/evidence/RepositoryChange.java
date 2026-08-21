package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.time.Instant;

import com.hackathon.incident_remediation_agent.git.DraftPullRequest;

/**
 * What last changed on the repository's base branch, and the pull request that put it there.
 *
 * <p>The diff is the head commit against its first parent, on the assumption that whatever was
 * live before the newest change was stable. For a squashed or merged pull request that first
 * parent is the branch tip the PR merged into, so the diff is the whole PR rather than one commit
 * of it.
 *
 * <p>This is small and high-signal in a way whole files are not: the change that broke a service
 * is usually the last one that landed, and a stack frame plus a recent diff pins it down far
 * faster than 1,000 lines of unchanged code.
 *
 * @param diff               unified patches, one per changed file, bounded by the collector
 * @param truncatedFiles     files whose patch was dropped to stay inside that bound
 * @param revertPullRequest  the draft this agent opened to undo the change, filled in after
 *                           collection and null when nothing was written
 */
public record RepositoryChange(
    String commitSha,
    String commitMessage,
    Instant committedAt,
    String diff,
    int truncatedFiles,
    PullRequest lastPullRequest,
    DraftPullRequest revertPullRequest
) {

    public RepositoryChange withRevert(DraftPullRequest revert) {
        return new RepositoryChange(this.commitSha, this.commitMessage, this.committedAt,
            this.diff, this.truncatedFiles, this.lastPullRequest, revert);
    }

    /** @param mergedAt when it landed on the base branch, which is the closest thing this repository has to a deploy time */
    public record PullRequest(int number, String title, URI url, Instant mergedAt) {}
}
