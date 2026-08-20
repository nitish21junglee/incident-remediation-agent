package com.hackathon.incident_remediation_agent.ai;

import java.util.Optional;
import java.util.Set;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;

/**
 * Picks which repository the incident points at, from the evidence.
 *
 * <p>The candidate set is supplied by the caller and is the operator's allowlist. Returning a name
 * outside it is treated as no answer, so a hallucinated or injected repository cannot widen what
 * the agent is able to clone and push to.
 */
public interface AiRepositorySelector {

    /**
     * @param allowed repository names the agent is permitted to touch
     * @return the chosen name, or empty when the evidence does not point at one
     */
    Optional<String> select(EvidencePack pack, Set<String> allowed);
}
