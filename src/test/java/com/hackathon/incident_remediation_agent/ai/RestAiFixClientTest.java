package com.hackathon.incident_remediation_agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

import tools.jackson.databind.ObjectMapper;

/**
 * The reply is prose from a model, not an API payload: it arrives fenced, and often with an
 * explanation after the fence. Rejecting it costs the investigation a candidate model.
 */
class RestAiFixClientTest {

    private static final String BASE = "https://ai.example.com/v1";

    private static final String SOURCE = "src/main/java/com/acme/demo/Mapper.java";

    private static final String FIXED = "class Mapper { /* guarded */ }";

    private static final String JSON = """
        {"probableFix":true,"hypothesis":"the payload is null","summary":"Guard the null payload",
         "files":{"%s":"%s"}}""".formatted(SOURCE, FIXED);

    private MockRestServiceServer server;

    private RestAiFixClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        AgentProperties properties = new AgentProperties("live", "P2UX5VH", null, null, null, null,
            null, new AgentProperties.Ai(BASE, "key", List.of("test-model"), Map.of()), null);
        this.client = new RestAiFixClient(builder, properties, new AiModelSelector(properties));
    }

    @Test
    void readsJsonWrappedInAFence() {
        expectCompletion("```json\n" + JSON + "\n```");

        FixProposal proposal = this.client.propose(pack(), Map.of(SOURCE, "class Mapper {}"));

        this.server.verify();
        assertThat(proposal.probableFix()).isTrue();
        assertThat(proposal.summary()).isEqualTo("Guard the null payload");
        assertThat(proposal.files()).containsEntry(SOURCE, FIXED);
    }

    /** A second fence in the trailing prose used to leave the first one inside the JSON slice. */
    @Test
    void readsFencedJsonFollowedByAFencedExplanation() {
        expectCompletion("""
            ```json
            %s
            ```

            The guard is:
            ```java
            if (payload == null) { return; }
            ```
            """.formatted(JSON));

        FixProposal proposal = this.client.propose(pack(), Map.of(SOURCE, "class Mapper {}"));

        this.server.verify();
        assertThat(proposal.probableFix()).isTrue();
        assertThat(proposal.files()).containsEntry(SOURCE, FIXED);
    }

    private void expectCompletion(String content) {
        String body = new ObjectMapper().writeValueAsString(
            Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        this.server.expect(requestTo(BASE + "/chat/completions")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static EvidencePack pack() {
        IncidentAlert alert = new IncidentAlert("01JDEMOEVENT", "PINCIDENT",
            "reward-service error rate increased", "P2UX5VH", "reward-service",
            Instant.parse("2026-08-14T02:14:00Z"),
            URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
        return new EvidencePack(alert,
            new JiraTicket("SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1")),
            new LogEvidence(184, "NullPointerException", List.of("sample"), null),
            new MetricEvidence(0.001, 0.4, false, null, List.of()),
            new DeploymentEvidence("v1.4.2", "abc123", null,
                Instant.parse("2026-08-14T02:04:00Z")),
            null, "application_error", "evidence-sha");
    }
}
