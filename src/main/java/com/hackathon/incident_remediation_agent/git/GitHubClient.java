package com.hackathon.incident_remediation_agent.git;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

/**
 * Draft pull requests only. Do not add methods for merge, review approval, release, workflow
 * dispatch, or deployment.
 */
public interface GitHubClient {

    DraftPullRequest createDraft(EvidencePack pack, FixProposal proposal, WorkspaceResult workspace);
}
