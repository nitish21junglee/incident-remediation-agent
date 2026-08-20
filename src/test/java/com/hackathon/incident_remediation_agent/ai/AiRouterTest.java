package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

class AiRouterTest {

    private static final String CONTEXT_FILE = "src/main/java/Mapper.java";

    private static final FixProposal PROPOSAL = new FixProposal(
        true, "null payment type", "Handle missing payment type", "diff --git a/x b/x\n");

    @TempDir
    Path repository;

    private AiFixClient aiFixClient;

    private AiRouter router;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repository.resolve("src/main/java"));
        Files.writeString(repository.resolve(CONTEXT_FILE), "class Mapper {}");
        this.aiFixClient = mock(AiFixClient.class);
        this.router = routerMapping("PDEMO");
    }

    @Test
    void requestsAFixForApplicationErrors() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);

        Optional<FixProposal> proposal = router.route(pack("application_error"));

        assertThat(proposal).contains(PROPOSAL);
        verify(aiFixClient).propose(any(EvidencePack.class),
            eqMap(Map.of(CONTEXT_FILE, "class Mapper {}")));
    }

    @Test
    void skipsInfrastructureClassification() {
        assertThat(router.route(pack("infrastructure_or_dependency"))).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsUnknownClassification() {
        assertThat(router.route(pack("unknown"))).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsWhenJiraKeyIsMissing() {
        EvidencePack pack = new EvidencePack(alert(), null, logs("NullPointerException"),
            metrics(), deployment(), "application_error", "v1");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsWhenEvidenceVersionIsMissing() {
        EvidencePack pack = new EvidencePack(alert(), ticket(), logs("NullPointerException"),
            metrics(), deployment(), "application_error", "  ");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    /** An unmapped service must not fall back to some other repository. */
    @Test
    void skipsWhenNoRepositoryIsMappedForTheService() {
        assertThat(routerMapping("PSOMETHINGELSE").route(pack("application_error"))).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsWhenTopErrorIsBlank() {
        EvidencePack pack = new EvidencePack(alert(), ticket(), logs("  "),
            metrics(), deployment(), "application_error", "v1");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void returnsEmptyWhenTheModelReportsNoProbableFix() {
        when(aiFixClient.propose(any(), anyMap()))
            .thenReturn(new FixProposal(false, "no idea", "none", "diff --git a/x b/x\n"));

        assertThat(router.route(pack("application_error"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheDiffIsBlank() {
        when(aiFixClient.propose(any(), anyMap()))
            .thenReturn(new FixProposal(true, "null payment type", "summary", "   "));

        assertThat(router.route(pack("application_error"))).isEmpty();
    }

    private void verifyNoAiCall() {
        verify(aiFixClient, never()).propose(any(), anyMap());
    }

    /** @param mappedServiceId the only service the resolver knows about */
    private AiRouter routerMapping(String mappedServiceId) {
        AgentProperties properties = new AgentProperties(
            "fixture", "PDEMO", null, null, null, null, null,
            new AgentProperties.GitHub("https://api.github.com", "token",
                "hackathon/incident", List.of(".github/workflows/"),
                Map.of("demo-api", new AgentProperties.RepositoryTarget(
                    "acme/demo-api", "main", repository.toString(),
                    List.of(CONTEXT_FILE), List.of("true"))),
                Map.of(mappedServiceId, "demo-api")));
        return new AiRouter(aiFixClient, new RepositoryContextReader(),
            new RepositoryResolver(properties));
    }

    private EvidencePack pack(String classification) {
        return new EvidencePack(alert(), ticket(), logs("NullPointerException in Mapper.java:44"),
            metrics(), deployment(), classification, "v1");
    }

    private static IncidentAlert alert() {
        return new IncidentAlert("01JDEMOEVENT", "PINCIDENT", "demo-api error rate increased",
            "PDEMO", Instant.parse("2026-08-14T02:14:00Z"),
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    private static JiraTicket ticket() {
        return new JiraTicket("SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1"));
    }

    private static LogEvidence logs(String topError) {
        return new LogEvidence(143, topError, List.of("sample one", "sample two"),
            URI.create("https://splunk.example/app/search"));
    }

    private static MetricEvidence metrics() {
        return new MetricEvidence(0.4, 6.1, false, URI.create("https://signalfx.example/dashboard"));
    }

    private static DeploymentEvidence deployment() {
        return new DeploymentEvidence("v1.4.2", "abc123",
            URI.create("https://github.com/acme/demo-api/commit/abc123"),
            Instant.parse("2026-08-14T01:55:00Z"));
    }

    private static Map<String, String> eqMap(Map<String, String> expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> expected.equals(actual));
    }
}
