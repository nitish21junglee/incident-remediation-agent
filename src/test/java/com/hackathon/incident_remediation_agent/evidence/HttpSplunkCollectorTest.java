package com.hackathon.incident_remediation_agent.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

class HttpSplunkCollectorTest {

    private static final String BASE = "https://splunk.example";

    private static final String EXPORT = BASE + "/services/search/jobs/export";

    private static final String SEARCH_LINK = "https://splunk.example/app/search";

    private static final String QUERY = "index=demo level=ERROR";

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
    void foldsTheSearchResultsIntoLogEvidence() {
        respondWith(readExportResponse());

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(3);
        assertThat(evidence.topError())
            .isEqualTo("java.lang.IllegalStateException: Simulated popup failure for userId=1");
        assertThat(evidence.sourceUrl()).isEqualTo(URI.create(SEARCH_LINK));
        server.verify();
    }

    /**
     * The search has to reach Splunk as a generating command, scoped to the incident's service and
     * to the window around it, or the export streams the whole account back.
     */
    @Test
    void searchesTheIncidentServiceOverTheWindowAroundTheAlert() {
        server.expect(requestTo(EXPORT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer splunk-token"))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formData(form()))
            .andRespond(withSuccess(readExportResponse(), MediaType.APPLICATION_JSON));

        collector.collect(alert);

        server.verify();
    }

    /** The origin and entry-point frames are the only thing naming a file the fix could touch. */
    @Test
    void rendersTheExceptionFramesAsStackFrames() {
        respondWith(readExportResponse());

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
            {"preview":false,"result":{"level":"INFO","_time":"t1","logger":"l","message":"started"}}
            {"preview":false,"result":{"level":"error","_time":"t2","logger":"l","message":"boom"}}
            """);

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(1);
        assertThat(evidence.topError()).isEqualTo("boom");
    }

    /** Preview lines are partial results for a running search; the final lines repeat them. */
    @Test
    void ignoresPreviewResults() {
        respondWith("""
            {"preview":true,"result":{"level":"ERROR","_time":"t1","logger":"l","message":"boom"}}
            {"preview":false,"result":{"level":"ERROR","_time":"t1","logger":"l","message":"boom"}}
            """);

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(1);
    }

    /** Export streams, so a dropped connection leaves a half-written line after good results. */
    @Test
    void keepsTheResultsBeforeATruncatedLine() {
        respondWith("""
            {"preview":false,"result":{"level":"ERROR","_time":"t1","logger":"l","message":"boom"}}
            {"preview":false,"result":{"level":"ERROR","_ti""");

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isEqualTo(1);
        assertThat(evidence.topError()).isEqualTo("boom");
    }

    /**
     * Empty evidence blanks {@code topError}, which classifies the incident as unknown and stops
     * the AI path. A Splunk outage must not abandon the run, and must not reach the model either.
     */
    @Test
    void collectsNoLogsWhenTheSearchFails() {
        server.expect(requestTo(EXPORT)).andRespond(withServerError());

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isZero();
        assertThat(evidence.topError()).isNull();
        assertThat(evidence.samples()).isEmpty();
        assertThat(evidence.sourceUrl()).isNull();
    }

    @Test
    void collectsNoLogsWhenTheSearchMatchesNothing() {
        respondWith("");

        LogEvidence evidence = collector.collect(alert);

        assertThat(evidence.errorCount()).isZero();
        assertThat(evidence.topError()).isNull();
    }

    private void respondWith(String body) {
        server.expect(requestTo(EXPORT))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static MultiValueMap<String, String> form() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("search", "search " + QUERY + " service=\"darsrftp-service-dev\"");
        form.add("earliest_time", String.valueOf(Instant.parse("2026-08-21T15:55:00Z").getEpochSecond()));
        form.add("latest_time", String.valueOf(Instant.parse("2026-08-21T16:40:00Z").getEpochSecond()));
        form.add("output_mode", "json");
        form.add("count", "500");
        return form;
    }

    /**
     * One trace as the search export returns it — all three loggers, a trailing INFO event, and the
     * nested exception fields flattened to the dotted names Splunk gives them.
     */
    private static String readExportResponse() {
        try (InputStream stream = HttpSplunkCollectorTest.class
            .getResourceAsStream("/splunk-search-export.json")) {
            assertThat(stream).as("missing /splunk-search-export.json").isNotNull();
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
            new AgentProperties.Splunk(baseUrl, "splunk-token", QUERY, SEARCH_LINK),
            null,
            null,
            null,
            null);
    }
}
