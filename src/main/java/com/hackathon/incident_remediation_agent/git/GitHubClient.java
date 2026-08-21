package com.hackathon.incident_remediation_agent.git;

import java.util.List;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.RepositoryChange;

/**
 * Draft pull requests only. Do not add methods for merge, review approval, release, workflow
 * dispatch, or deployment.
 */
public interface GitHubClient {

    /**
     * Creates the branch, the commit and the draft pull request, or reports what it would have done
     * when {@code agent.github.push-enabled} is false.
     *
     * @param changedFiles the paths {@link FixGate} approved
     */
    FixSubmission submitFix(
        EvidencePack pack,
        FixProposal proposal,
        AgentProperties.RepositoryTarget target,
        List<String> changedFiles
    );

    /**
     * Opens a draft pull request undoing {@code change}: every file that commit touched is put
     * back to its content at the commit before it. An escape hatch for the on-call, offered
     * alongside the investigation rather than in place of it.
     *
     * <p>Still a draft, and still refused when {@code agent.github.push-enabled} is false.
     *
     * @return the draft, or null when nothing was written
     */
    DraftPullRequest revert(
        EvidencePack pack,
        RepositoryChange change,
        AgentProperties.RepositoryTarget target
    );
}
