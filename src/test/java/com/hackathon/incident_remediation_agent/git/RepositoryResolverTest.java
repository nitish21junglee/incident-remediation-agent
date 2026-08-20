package com.hackathon.incident_remediation_agent.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

/**
 * Repository selection must be a deterministic config lookup. Incident and log text is untrusted,
 * so nothing derived from it may influence which repository the agent can write to.
 */
class RepositoryResolverTest {

    private static final AgentProperties.RepositoryTarget DEMO =
        new AgentProperties.RepositoryTarget("acme/demo-api", "main", "/tmp/demo-api",
            List.of("src/main/java/Mapper.java"), List.of("./mvnw", "test"));

    private static final Map<String, AgentProperties.RepositoryTarget> KNOWN =
        Map.of("demo-api", DEMO);

    @Test
    void resolvesTheTargetForAMappedService() {
        RepositoryResolver resolver = resolver(KNOWN, Map.of("PDEMO", "demo-api"));

        assertThat(resolver.resolve(alert("PDEMO"))).contains(DEMO);
    }

    @Test
    void returnsEmptyForAnUnmappedService() {
        RepositoryResolver resolver = resolver(KNOWN, Map.of("PDEMO", "demo-api"));

        assertThat(resolver.resolve(alert("POTHER"))).isEmpty();
    }

    @Test
    void matchesServiceIdsCaseInsensitively() {
        RepositoryResolver resolver = resolver(KNOWN, Map.of("pdemo", "DEMO-API"));

        assertThat(resolver.resolve(alert("PDEMO"))).contains(DEMO);
    }

    @Test
    void returnsEmptyWhenNoTargetsAreConfigured() {
        assertThat(resolver(Map.of(), Map.of()).resolve(alert("PDEMO"))).isEmpty();
    }

    /** A half-filled target would fail deep inside the workspace, so reject it up front. */
    @Test
    void rejectsTargetsMissingARepositorySlug() {
        RepositoryResolver resolver = resolver(Map.of("demo-api",
            new AgentProperties.RepositoryTarget("  ", "main", "/tmp/demo-api",
                List.of("f.java"), List.of("true"))), Map.of("PDEMO", "demo-api"));

        assertThat(resolver.resolve(alert("PDEMO"))).isEmpty();
    }

    @Test
    void rejectsTargetsMissingALocalPath() {
        RepositoryResolver resolver = resolver(Map.of("demo-api",
            new AgentProperties.RepositoryTarget("acme/demo-api", "main", null,
                List.of("f.java"), List.of("true"))), Map.of("PDEMO", "demo-api"));

        assertThat(resolver.resolve(alert("PDEMO"))).isEmpty();
    }

    @Test
    void rejectsTargetsWithNoContextFiles() {
        RepositoryResolver resolver = resolver(Map.of("demo-api",
            new AgentProperties.RepositoryTarget("acme/demo-api", "main", "/tmp/demo-api",
                List.of(), List.of("true"))), Map.of("PDEMO", "demo-api"));

        assertThat(resolver.resolve(alert("PDEMO"))).isEmpty();
    }

    /** The allowlist bounds what the agent can reach even if a name arrives from elsewhere. */
    @Test
    void resolvesByNameOnlyForAllowlistedRepositories() {
        RepositoryResolver resolver = resolver(KNOWN, Map.of());

        assertThat(resolver.resolveByName("demo-api")).contains(DEMO);
        assertThat(resolver.resolveByName("someone-elses-repo")).isEmpty();
        assertThat(resolver.allowedRepositories()).containsExactly("demo-api");
    }

    private static RepositoryResolver resolver(
        Map<String, AgentProperties.RepositoryTarget> repositories,
        Map<String, String> serviceRepositories
    ) {
        return new RepositoryResolver(new AgentProperties(
            "fixture", "PDEMO", null, null, null, null, null,
            new AgentProperties.GitHub("https://api.github.com", "token",
                "hackathon/incident", List.of(".github/workflows/"),
                repositories, serviceRepositories)));
    }

    private static IncidentAlert alert(String serviceId) {
        return new IncidentAlert("01JDEMOEVENT", "PINCIDENT", "demo-api error rate increased",
            serviceId, "demo-api", Instant.parse("2026-08-14T02:14:00Z"),
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }
}
