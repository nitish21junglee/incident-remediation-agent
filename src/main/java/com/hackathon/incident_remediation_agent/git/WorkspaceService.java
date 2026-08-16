package com.hackathon.incident_remediation_agent.git;

import org.springframework.stereotype.Service;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

/**
 * Clones the configured repository into an ephemeral directory, applies the proposed diff, enforces
 * the protected-path and changed-file gates, runs the configured validation command, and pushes the
 * branch only after every gate passes.
 *
 * <p>Contract stub only — the body is filled in by Task 8.
 */
@Service
public class WorkspaceService {

    /**
     * @throws PatchValidationException before any push, for every expected rejection
     */
    public WorkspaceResult validateAndPush(EvidencePack pack, FixProposal proposal) {
        throw new UnsupportedOperationException("Task 8: implement validateAndPush");
    }
}
