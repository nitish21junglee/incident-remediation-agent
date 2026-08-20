package com.hackathon.incident_remediation_agent.ai;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;

/**
 * Decides whether an incident is eligible for AI investigation and, if so, returns the proposal.
 * Returns empty when the evidence does not support a bounded code change.
 *
 * <p>Routing is deterministic and happens before any model call, so an ineligible incident costs
 * nothing and leaks no repository content. This class never creates a workspace, runs a command, or
 * calls GitHub.
 */
@Component
public class AiRouter {

    private static final String ELIGIBLE_CLASSIFICATION = "application_error";

    private static final Logger log = LoggerFactory.getLogger(AiRouter.class);

    private final AiFixClient aiFixClient;
    private final AiRepositorySelector repositorySelector;
    private final RepositoryContextReader contextReader;
    private final RepositoryResolver repositories;

    AiRouter(AiFixClient aiFixClient, AiRepositorySelector repositorySelector,
             RepositoryContextReader contextReader, RepositoryResolver repositories) {
        this.aiFixClient = aiFixClient;
        this.repositorySelector = repositorySelector;
        this.contextReader = contextReader;
        this.repositories = repositories;
    }

    public Optional<RoutedFix> route(EvidencePack pack) {
        String ineligible = ineligibilityReason(pack);
        if (ineligible != null) {
            log.info("Skipping AI investigation: {}", ineligible);
            return Optional.empty();
        }

        Set<String> allowed = this.repositories.allowedRepositories();
        if (allowed.isEmpty()) {
            log.warn("No repositories are configured; skipping code investigation");
            return Optional.empty();
        }

        Optional<String> chosen = this.repositorySelector.select(pack, allowed);
        if (chosen.isEmpty()) {
            log.info("Evidence did not point at a repository for {}", pack.ticket().key());
            return Optional.empty();
        }

        // The choice is validated against the allowlist before anything is read or cloned, so a
        // hallucinated or injected name cannot widen what the agent can reach.
        Optional<AgentProperties.RepositoryTarget> resolved =
            this.repositories.resolveByName(chosen.get());
        if (resolved.isEmpty()) {
            log.warn("Chosen repository '{}' is not allowlisted; skipping", chosen.get());
            return Optional.empty();
        }
        AgentProperties.RepositoryTarget target = resolved.get();

        Map<String, String> repositoryFiles = this.contextReader.read(
            Path.of(target.directory()), target.contextFiles());

        FixProposal proposal = this.aiFixClient.propose(pack, repositoryFiles);
        if (proposal == null || !proposal.probableFix()) {
            log.info("Model reported no probable fix for {}", pack.ticket().key());
            return Optional.empty();
        }
        if (isBlank(proposal.unifiedDiff())) {
            log.info("Model returned a probable fix with no diff for {}", pack.ticket().key());
            return Optional.empty();
        }
        return Optional.of(new RoutedFix(chosen.get(), proposal));
    }

    /**
     * @return why the incident is ineligible, or {@code null} when AI should run
     */
    private String ineligibilityReason(EvidencePack pack) {
        if (!ELIGIBLE_CLASSIFICATION.equals(pack.classification())) {
            return "classification is " + pack.classification();
        }
        if (pack.ticket() == null || isBlank(pack.ticket().key())) {
            return "no Jira ticket";
        }
        if (isBlank(pack.evidenceVersion())) {
            return "no evidence version";
        }
        if (pack.logs() == null || isBlank(pack.logs().topError())) {
            return "no top error in the logs";
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
