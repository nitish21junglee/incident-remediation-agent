package com.hackathon.incident_remediation_agent.ai;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

/**
 * Decides whether an incident is eligible for AI investigation and, if so, returns the proposal.
 * Returns empty when the evidence does not support a bounded code change.
 *
 * <p>Contract stub only — the body is filled in by Task 7.
 */
@Component
public class AiRouter {

    public Optional<FixProposal> route(EvidencePack pack) {
        throw new UnsupportedOperationException("Task 7: implement route");
    }
}
