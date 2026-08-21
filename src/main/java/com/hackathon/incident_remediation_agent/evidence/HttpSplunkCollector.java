package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads error events from an HTTP log endpoint and folds them into {@link LogEvidence}.
 *
 * <p>The endpoint is currently a Postman mock returning Splunk-shaped JSON: a flat array of events
 * carrying the service, pod and logger, where the events raised by the global exception handler
 * also carry an {@code exception} object with the type, message, origin frame and entry-point
 * frame. Those two frames are the only thing telling the model which file to open, so they are
 * rendered into the samples as stack frames rather than dropped.
 *
 * <p>The body is parsed from text rather than bound directly: the mock answers with
 * {@code Content-Type: text/html} whatever the request asks for, and no message converter will
 * bind that to JSON.
 *
 * <p>Neither {@code agent.splunk.token} nor {@code agent.splunk.query} is sent. The mock accepts
 * neither. Both belong to the real Splunk search API, along with a different path.
 *
 * <p>A failed, unparseable or empty fetch yields empty evidence rather than an exception, matching
 * {@code SignalFlowSignalFxCollector}: a blank {@code topError} makes {@code EvidenceClassifier}
 * return {@code unknown}, which stops the AI path. Losing logs fails towards proposing nothing,
 * never towards proposing a blind fix.
 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "live")
public class HttpSplunkCollector implements SplunkCollector {

    private static final Logger log = LoggerFactory.getLogger(HttpSplunkCollector.class);

    private static final String LOGS_PATH = "/fetchLogs";

    private static final String ERROR_LEVEL = "ERROR";

    /** Samples are quoted verbatim into a Jira comment and an AI prompt, so the list is bounded. */
    private static final int MAX_SAMPLED_EVENTS = 10;

    private static final LogEvidence NO_LOGS = new LogEvidence(0, null, List.of(), null);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final URI searchUrl;

    HttpSplunkCollector(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.Splunk splunk = properties.splunk();
        // A configured trailing slash would otherwise yield '...//fetchLogs'.
        this.restClient = builder.baseUrl(splunk.baseUrl().replaceAll("/+$", "")).build();
        this.searchUrl = splunk.searchLink() == null || splunk.searchLink().isBlank()
            ? null
            : URI.create(splunk.searchLink());
    }

    @Override
    public LogEvidence collect(IncidentAlert alert) {
        List<JsonNode> errors = errorEvents(fetch(alert));
        if (errors.isEmpty()) {
            log.warn("Log endpoint returned no ERROR events for incident {}", alert.incidentId());
            return NO_LOGS;
        }
        log.info("Collected {} ERROR events for incident {}", errors.size(), alert.incidentId());
        return new LogEvidence(errors.size(), topError(errors), samples(errors), this.searchUrl);
    }

    private JsonNode fetch(IncidentAlert alert) {
        try {
            String body = this.restClient.get().uri(LOGS_PATH).retrieve().body(String.class);
            return body == null || body.isBlank() ? null : this.objectMapper.readTree(body);
        }
        catch (RestClientException | JacksonException exception) {
            log.warn("Log fetch failed for incident {}; collecting no logs",
                alert.incidentId(), exception);
            return null;
        }
    }

    private static List<JsonNode> errorEvents(JsonNode events) {
        List<JsonNode> errors = new ArrayList<>();
        if (events == null || !events.isArray()) {
            return errors;
        }
        for (JsonNode event : events) {
            if (ERROR_LEVEL.equalsIgnoreCase(event.path("level").asString(""))) {
                errors.add(event);
            }
        }
        return errors;
    }

    /**
     * Events carrying an exception win over plain log lines. The exception signature is the string
     * {@code EvidenceClassifier} matches its application-error markers against, and an aspect's
     * request line carries no exception type at all.
     */
    private static String topError(List<JsonNode> errors) {
        List<JsonNode> withException = errors.stream()
            .filter(event -> event.path("exception").isObject())
            .toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        (withException.isEmpty() ? errors : withException)
            .forEach(event -> counts.merge(signature(event), 1L, Long::sum));

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /** Events are sampled in the order the endpoint returned them, which Splunk orders newest first. */
    private static List<String> samples(List<JsonNode> errors) {
        List<String> lines = new ArrayList<>();
        errors.stream().limit(MAX_SAMPLED_EVENTS).forEach(event -> {
            lines.add("%s %s [%s] %s".formatted(
                event.path("timestamp").asString(""),
                event.path("level").asString(""),
                event.path("logger").asString(""),
                event.path("message").asString("")));

            JsonNode exception = event.path("exception");
            if (exception.isObject()) {
                lines.add(signature(event));
                frame(lines, exception.path("origin").asString(""));
                frame(lines, exception.path("entryPoint").asString(""));
            }
        });
        return List.copyOf(lines);
    }

    private static void frame(List<String> lines, String frame) {
        if (!frame.isBlank()) {
            lines.add("\tat " + frame);
        }
    }

    private static String signature(JsonNode event) {
        JsonNode exception = event.path("exception");
        return exception.isObject()
            ? exception.path("type").asString("") + ": " + exception.path("message").asString("")
            : event.path("message").asString("");
    }
}
