package com.hackathon.incident_remediation_agent.ai;

import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;

/**
 * Deterministic stand-in for model-driven repository selection. Prefers the repository the operator
 * mapped to the incident's service, and otherwise falls back to the sole allowlisted repository.
 *
 * <p>Registered in every mode, not just fixture mode: it is the only implementation, and the
 * workflow cannot run without one. Gate it on {@code agent.mode} once a model-driven selector
 * exists to take over in live mode.
 */
@Component
public class FixtureAiRepositorySelector implements AiRepositorySelector {

    private final RepositoryResolver repositories;

    FixtureAiRepositorySelector(RepositoryResolver repositories) {
        this.repositories = repositories;
    }

    @Override
    public Optional<String> select(EvidencePack pack, Set<String> allowed) {
        Optional<String> mapped = this.repositories.repositoryNameFor(pack.alert())
            .filter(allowed::contains);
        if (mapped.isPresent()) {
            return mapped;
        }
        return allowed.size() == 1 ? allowed.stream().findFirst() : Optional.empty();
    }
}
