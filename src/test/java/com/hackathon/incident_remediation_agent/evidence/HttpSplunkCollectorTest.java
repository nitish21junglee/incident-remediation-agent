package com.hackathon.incident_remediation_agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

class HttpSplunkCollectorTest {

    private static final String BASE = "https://logs.example";

    private static final String SEARCH_LINK = "https://splunk.example/app/search";

    private final IncidentAlert alert = new IncidentAlert(
        "01JDEMOEVENT",
        "PINCIDENT",
        "darsrftp-service-dev error rate increased",
        "P2UX5VH",
        "darsrftp-service-dev",
        Instant.parse("2026-08-21T16:25:00Z"),
        URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));

    private MockRestServiceServer server;

    private HttpSplunkCollector collector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.collector = new HttpSplunkCollector(builder, properties(BASE + "/"));
    }

    @Test
    void foldsTheEndpointResponseIntoLogEvidence() {
        respondWith(readMockResponse());

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(3);
        assertThat(evidence.topError())
            .isEqualTo("java.lang.IllegalStateException: Simulated popup failure for userId=1");
        assertThat(evidence.sourceUrl()).isEqualTo(URI.create(SEARCH_LINK));
        server.verify();
    }

    /** The origin and entry-point frames are the only thing naming a file the fix could touch. */
    @Test
    void rendersTheExceptionFramesAsStackFrames() {
        respondWith(readMockResponse());

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.samples())
            .startsWith(
                "2026-08-21T16:25:13.573+0000 ERROR [c.f.r.e.GlobalExceptionHandler] "
                    + "Unexpected exception occurred",
                "java.lang.IllegalStateException: Simulated popup failure for userId=1",
                "\tat com.flutter.reward_service.service.impl.RewardServiceImpl"
                    + ".getPopup(RewardServiceImpl.java:299)",
                "\tat com.flutter.reward_service.controller.RewardController"
                    + ".getPopup(RewardController.java:49)")
            .contains("2026-08-21T16:25:13.572+0000 ERROR [c.f.r.aop.ControllerLoggingAspect] "
                + "GET /rs/v1/rewards/popup | method=RewardController.getPopup failed in 1ms | "
                + "error=Simulated popup failure for userId=1");
    }

    @Test
    void ignoresEventsThatAreNotErrors() {
        respondWith("""
            [
              {"level":"INFO","timestamp":"t1","logger":"l","message":"started"},
              {"level":"error","timestamp":"t2","logger":"l","message":"boom"}
            ]
            """);

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(1);
        assertThat(evidence.topError()).isEqualTo("boom");
    }

    /**
     * Empty evidence blanks {@code topError}, which classifies the incident as unknown and stops
     * the AI path. A log outage must not abandon the run, and must not reach the model either.
     */
    @Test
    void collectsNoLogsWhenTheEndpointFails() {
        server.expect(requestTo(BASE + "/fetchLogs")).andRespond(withServerError());

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isZero();
        assertThat(evidence.topError()).isNull();
        assertThat(evidence.samples()).isEmpty();
        assertThat(evidence.sourceUrl()).isNull();
    }

    @Test
    void collectsNoLogsWhenTheResponseIsNotAnArray() {
        respondWith("{\"error\":\"no such index\"}");

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isZero();
        assertThat(evidence.topError()).isNull();
    }

    private void respondWith(String body) {
        server.expect(requestTo(BASE + "/fetchLogs"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.TEXT_HTML));
    }

    /**
     * One trace lifted verbatim from what the mock endpoint returns — all three loggers and all
     * three payload shapes ({@code exception}, {@code http}, {@code context}) — served with the
     * endpoint's real {@code text/html} content type.
     */
    private static String readMockResponse() {
        try (InputStream stream = HttpSplunkCollectorTest.class
            .getResourceAsStream("/splunk-fetch-logs.json")) {
            assertThat(stream).as("missing /splunk-fetch-logs.json").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AgentProperties properties(String baseUrl) {
        return new AgentProperties(
            "live",
            "P2UX5VH",
            null,
            null,
            new AgentProperties.Splunk(baseUrl, "fixture-token",
                "search index=demo level=ERROR", SEARCH_LINK),
            null,
            null,
            null,
            null);
    }
}
