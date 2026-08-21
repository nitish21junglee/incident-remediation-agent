package com.hackathon.incident_remediation_agent.git;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * Finds the repository that owns a failing service, in two configured hops: the PagerDuty service
 * id selects a short repository name, and that name selects the GitHub slug, base branch and the
 * files that may be read and rewritten.
 *
 * <p>The name-to-target map is an allowlist. A repository absent from it can never be read or
 * written, whatever the incident text, log samples or model output happen to say. That
 * matters because those are all untrusted input: without the allowlist, whoever can write a log
 * line could steer the agent at another repository.
 *
 * <p>Anything unmapped or half-configured resolves to empty, which callers treat as "no code
 * investigation" rather than falling back to a default repository.
 */
@Component
public class RepositoryResolver {

    private static final Logger log = LoggerFactory.getLogger(RepositoryResolver.class);

    private final Map<String, AgentProperties.RepositoryTarget> byName = new LinkedHashMap<>();
    private final Map<String, String> repositoryNameByServiceId = new LinkedHashMap<>();

    public RepositoryResolver(AgentProperties properties) {
        AgentProperties.GitHub github = properties.github();
        if (github == null) {
            return;
        }
        if (github.repositories() != null) {
            github.repositories().forEach((name, target) -> this.byName.put(lower(name), target));
        }
        if (github.serviceRepositories() != null) {
            github.serviceRepositories()
                .forEach((serviceId, name) -> this.repositoryNameByServiceId.put(upper(serviceId), lower(name)));
        }
    }

    public Optional<AgentProperties.RepositoryTarget> resolve(IncidentAlert alert) {
        String repositoryName = this.repositoryNameByServiceId.get(upper(alert.serviceId()));
        if (repositoryName == null) {
            log.info("No repository mapped for service {}; skipping code investigation",
                alert.serviceId());
            return Optional.empty();
        }
        return resolveByName(repositoryName);
    }

    /**
     * Allowlist lookup by short repository name.
     *
     * @return the target, or empty when the name is unknown or its configuration is incomplete
     */
    public Optional<AgentProperties.RepositoryTarget> resolveByName(String repositoryName) {
        AgentProperties.RepositoryTarget target = this.byName.get(lower(repositoryName));
        if (target == null) {
            log.warn("Repository '{}' is not in the configured allowlist {}",
                repositoryName, this.byName.keySet());
            return Optional.empty();
        }
        String incomplete = incompleteReason(target);
        if (incomplete != null) {
            log.warn("Repository '{}' is configured but incomplete: {}", repositoryName, incomplete);
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * The configured owner of a service, when one is mapped. This is a hint for whoever chooses
     * the repository; it does not by itself grant access, since {@link #resolveByName} still
     * checks the allowlist.
     */
    public Optional<String> repositoryNameFor(IncidentAlert alert) {
        return Optional.ofNullable(this.repositoryNameByServiceId.get(upper(alert.serviceId())));
    }

    /** @return every repository the agent is permitted to touch */
    public Set<String> allowedRepositories() {
        return Set.copyOf(this.byName.keySet());
    }

    private static String incompleteReason(AgentProperties.RepositoryTarget target) {
        if (isBlank(target.slug())) {
            return "no GitHub slug";
        }
        if (target.contextFiles() == null || target.contextFiles().isEmpty()) {
            return "no context files";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
