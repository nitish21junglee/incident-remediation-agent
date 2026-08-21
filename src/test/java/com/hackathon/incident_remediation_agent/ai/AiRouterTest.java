package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.git.RepositoryResolver;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

class AiRouterTest {

    private static final String WRITABLE_PATH = "src/main/java/com/acme/demo/";

    private static final String CONTEXT_FILE = WRITABLE_PATH + "Mapper.java";

    private static final String CONTEXT_CONTENT = "class Mapper {}";

    /** The frame that has to choose {@link #CONTEXT_FILE}, since configuration no longer does. */
    private static final String FRAME = "\tat com.acme.demo.Mapper.map(Mapper.java:44)";

    private static final FixProposal PROPOSAL = new FixProposal(
        true, "null payment type", "Handle missing payment type",
        Map.of(CONTEXT_FILE, "class Mapper { /* guarded */ }"));

    private AiFixClient aiFixClient;

    private GitHubContextReader contextReader;

    private AiRepositorySelector repositorySelector;

    private AiRouter router;

    @BeforeEach
    void setUp() {
        this.aiFixClient = mock(AiFixClient.class);
        this.contextReader = mock(GitHubContextReader.class);
        when(this.contextReader.read(any(), any()))
            .thenReturn(Map.of(CONTEXT_FILE, CONTEXT_CONTENT));
        this.repositorySelector = (pack, allowed) ->
            allowed.contains("demo-api") ? Optional.of("demo-api") : Optional.empty();
        this.router = routerMapping("PDEMO");
    }

    @Test
    void requestsAFixForTheRepositoryTheModelChose() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);

        Optional<RoutedFix> routed = router.route(pack("application_error"));

        assertThat(routed).isPresent();
        assertThat(routed.get().repositoryName()).isEqualTo("demo-api");
        assertThat(routed.get().proposal()).isEqualTo(PROPOSAL);
        assertThat(routed.get().originalFiles()).containsExactly(entry(CONTEXT_FILE, CONTEXT_CONTENT));
        verify(aiFixClient).propose(any(EvidencePack.class),
            eqMap(Map.of(CONTEXT_FILE, CONTEXT_CONTENT)));
    }

    /** The model's choice is validated against the allowlist before anything is read. */
    @Test
    void skipsWhenTheModelChoosesARepositoryOutsideTheAllowlist() {
        this.repositorySelector = (pack, allowed) -> Optional.of("someone-elses-repo");
        this.router = routerMapping("PDEMO");

        assertThat(router.route(pack("application_error"))).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsWhenTheEvidenceDoesNotPointAtARepository() {
        this.repositorySelector = (pack, allowed) -> Optional.empty();
        this.router = routerMapping("PDEMO");

        assertThat(router.route(pack("application_error"))).isEmpty();
        verifyNoAiCall();
    }

    /**
     * Classification is a label on the evidence, not a gate. An infrastructure-looking incident
     * still reaches the model, because the stack frame is what decides whether there is anything
     * to look at.
     */
    @Test
    void investigatesRegardlessOfClassification() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);

        assertThat(router.route(pack("infrastructure_or_dependency"))).isPresent();
        assertThat(router.route(pack("unknown"))).isPresent();
    }

    @Test
    void skipsWhenJiraKeyIsMissing() {
        EvidencePack pack = new EvidencePack(alert(), null, logs("NullPointerException"),
            metrics(), deployment(), null, "application_error", "v1");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void skipsWhenEvidenceVersionIsMissing() {
        EvidencePack pack = new EvidencePack(alert(), ticket(), logs("NullPointerException"),
            metrics(), deployment(), null, "application_error", "  ");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    /** An unmapped service must not fall back to some other repository. */
    /**
     * The service-to-repository mapping is only a hint now. With the selector deciding, an
     * unmapped service no longer blocks the investigation.
     */
    @Test
    void proceedsWhenTheServiceHasNoConfiguredOwner() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);

        assertThat(routerMapping("PSOMETHINGELSE").route(pack("application_error")))
            .map(RoutedFix::repositoryName)
            .contains("demo-api");
    }

    /** A blank top error is no longer a gate either; the frames still name a file. */
    @Test
    void investigatesWhenTopErrorIsBlankButFramesRemain() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);
        EvidencePack pack = new EvidencePack(alert(), ticket(),
            new LogEvidence(143, "  ", List.of(FRAME), null),
            metrics(), deployment(), null, "application_error", "v1");

        assertThat(router.route(pack)).isPresent();
    }

    @Test
    void returnsEmptyWhenTheModelReportsNoProbableFix() {
        when(aiFixClient.propose(any(), anyMap()))
            .thenReturn(new FixProposal(false, "no idea", "none", Map.of()));

        assertThat(router.route(pack("application_error"))).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheModelChangesNoFiles() {
        when(aiFixClient.propose(any(), anyMap()))
            .thenReturn(new FixProposal(true, "null payment type", "summary", Map.of()));

        assertThat(router.route(pack("application_error"))).isEmpty();
    }

    /** Configuration no longer names a file, so evidence that names none means nothing to send. */
    @Test
    void skipsWhenNoStackFramePointsIntoTheRepository() {
        EvidencePack pack = new EvidencePack(alert(), ticket(),
            new LogEvidence(143, "NullPointerException", List.of(
                "\tat org.springframework.web.servlet.DispatcherServlet.doGet(DispatcherServlet.java:1072)",
                "\tat java.base/java.lang.Thread.run(Thread.java:1583)"), null),
            metrics(), deployment(), null, "application_error", "v1");

        assertThat(router.route(pack)).isEmpty();
        verifyNoAiCall();
    }

    /** Every frame-derived path can turn out not to be on the branch. That is not a fix. */
    @Test
    void skipsWhenNoneOfTheChosenFilesCouldBeRead() {
        when(this.contextReader.read(any(), any())).thenReturn(Map.of());

        assertThat(router.route(pack("application_error"))).isEmpty();
        verifyNoAiCall();
    }

    @Test
    void sendsOnlyTheFilesTheFramesNamed() {
        when(aiFixClient.propose(any(), anyMap())).thenReturn(PROPOSAL);

        router.route(pack("application_error"));

        verify(contextReader).read(any(), eqList(List.of(CONTEXT_FILE)));
    }

    private static List<String> eqList(List<String> expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> expected.equals(actual));
    }

    private void verifyNoAiCall() {
        verify(aiFixClient, never()).propose(any(), anyMap());
    }

    /** @param mappedServiceId the only service the resolver knows about */
    private AiRouter routerMapping(String mappedServiceId) {
        AgentProperties properties = new AgentProperties(
            "fixture", "PDEMO", null, null, null, null, null, null,
            new AgentProperties.GitHub("https://api.github.com", "token",
                "agent/incident", false, 5, List.of(".github/workflows/"),
                Map.of("demo-api", new AgentProperties.RepositoryTarget(
                    "acme/demo-api", "dev", List.of("src/main/java/"), List.of(WRITABLE_PATH))),
                Map.of(mappedServiceId, "demo-api")));
        return new AiRouter(aiFixClient, repositorySelector, new StackFrameFileSelector(),
            contextReader, new RepositoryResolver(properties));
    }

    private EvidencePack pack(String classification) {
        return new EvidencePack(alert(), ticket(), logs("NullPointerException in Mapper.java:44"),
            metrics(), deployment(), null, classification, "v1");
    }

    private static IncidentAlert alert() {
        return new IncidentAlert("01JDEMOEVENT", "PINCIDENT", "demo-api error rate increased",
            "PDEMO", "demo-api", Instant.parse("2026-08-14T02:14:00Z"),
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    private static JiraTicket ticket() {
        return new JiraTicket("SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1"));
    }

    private static LogEvidence logs(String topError) {
        return new LogEvidence(143, topError, List.of(topError, FRAME),
            URI.create("https://splunk.example/app/search"));
    }

    private static MetricEvidence metrics() {
        return new MetricEvidence(0.4, 6.1, false, URI.create("https://signalfx.example/dashboard"),
            List.of());
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
