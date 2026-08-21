package com.hackathon.incident_remediation_agent.ai;

import java.util.List;
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
 * nothing and leaks no repository content: the repository read only happens once the evidence has
 * already justified it. This class never writes anything — no branch, no commit, no pull request.
 */
@Component
public class AiRouter {

    private static final Logger log = LoggerFactory.getLogger(AiRouter.class);

    private final AiFixClient aiFixClient;
    private final AiRepositorySelector repositorySelector;
    private final StackFrameFileSelector fileSelector;
    private final GitHubContextReader contextReader;
    private final RepositoryResolver repositories;

    AiRouter(AiFixClient aiFixClient, AiRepositorySelector repositorySelector,
             StackFrameFileSelector fileSelector, GitHubContextReader contextReader,
             RepositoryResolver repositories) {
        this.aiFixClient = aiFixClient;
        this.repositorySelector = repositorySelector;
        this.fileSelector = fileSelector;
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

        // Which files, decided per incident from the evidence rather than from configuration. No
        // frames inside the repository means nothing worth showing a model, not a blind guess.
        List<String> contextPaths = this.fileSelector.select(pack.logs(), target);
        if (contextPaths.isEmpty()) {
            log.info("No stack frame in the evidence for {} points at a file in {}",
                pack.ticket().key(), chosen.get());
            return Optional.empty();
        }

        Map<String, String> repositoryFiles = this.contextReader.read(target, contextPaths);
        if (repositoryFiles.isEmpty()) {
            log.info("None of the frame-derived files for {} could be read from {}",
                pack.ticket().key(), chosen.get());
            return Optional.empty();
        }

        FixProposal proposal = this.aiFixClient.propose(pack, repositoryFiles);
        if (proposal == null || !proposal.probableFix()) {
            log.info("Model reported no probable fix for {}", pack.ticket().key());
            return Optional.empty();
        }
        if (proposal.files() == null || proposal.files().isEmpty()) {
            log.info("Model returned a probable fix with no file changes for {}",
                pack.ticket().key());
            return Optional.empty();
        }
        return Optional.of(new RoutedFix(chosen.get(), proposal, repositoryFiles));
    }

    /**
     * Classification no longer decides this. Every incident is investigated, and what stops a run
     * is the absence of something the investigation actually needs rather than an opinion about
     * the incident's category: a stack frame pointing into an allowlisted repository, and a file
     * behind it that can be read.
     *
     * @return why the incident cannot be investigated, or {@code null} when AI should run
     */
    private String ineligibilityReason(EvidencePack pack) {
        // Both are written by the workflow before this runs; they are guarded because every log
        // line and the pull request body dereference them.
        if (pack.ticket() == null || isBlank(pack.ticket().key())) {
            return "no Jira ticket";
        }
        if (isBlank(pack.evidenceVersion())) {
            return "no evidence version";
        }

        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
