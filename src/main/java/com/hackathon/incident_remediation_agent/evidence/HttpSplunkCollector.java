package com.hackathon.incident_remediation_agent.evidence;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the configured search against the Splunk search API and folds the matching events into
 * {@link LogEvidence}.
 *
 * <p>The call is {@code POST /services/search/jobs/export}, the one Splunk endpoint that runs a
 * search and streams its results in a single request — the alternative is create-job, poll for
 * completion, then fetch results, which buys nothing here. Authentication is
 * {@code Authorization: Bearer <agent.splunk.token>}, so the token has to be a Splunk
 * authentication token rather than a HEC token: HEC only ingests, it cannot search.
 *
 * <p>The search itself is {@code agent.splunk.query}, narrowed to the incident's own service and
 * to the window around {@link IncidentAlert#triggeredAt()}. Everything else on the account would
 * otherwise come back too, and the samples are what the model reads to decide which file to open.
 *
 * <p>Export answers with newline-delimited JSON, one {@code {"preview":…,"result":{…}}} object per
 * line rather than one JSON document, so the body is split on newlines and each line parsed on its
 * own. Preview lines are dropped: they are partial results for a search still running, and would
 * double-count events that the final lines repeat. Splunk flattens a JSON log event's nested
 * fields with dotted names, so the exception frames arrive as {@code exception.origin} and
 * {@code exception.entryPoint} — the only fields naming a file the fix could touch.
 *
 * <p>A failed, unparseable or empty search yields empty evidence rather than an exception, matching
 * {@code SignalFlowSignalFxCollector}: a blank {@code topError} makes {@code EvidenceClassifier}
 * return {@code unknown}, which stops the AI path. Losing logs fails towards proposing nothing,
 * never towards proposing a blind fix.
 */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "live")
public class HttpSplunkCollector implements SplunkCollector {

    private static final Logger log = LoggerFactory.getLogger(HttpSplunkCollector.class);

    /** Streams results as they are produced, so one request both runs and reads the search. */
    private static final String EXPORT_PATH = "/services/search/jobs/export";

    private static final String ERROR_LEVEL = "ERROR";

    /** Search window around the incident: enough before it to catch the first failing request. */
    private static final Duration LOOKBACK = Duration.ofMinutes(30);
    private static final Duration LOOKAHEAD = Duration.ofMinutes(15);

    /** Splunk counts every matching event towards this, so it bounds the search, not the samples. */
    private static final int MAX_EXPORTED_EVENTS = 500;

    /** Samples are quoted verbatim into a Jira comment and an AI prompt, so the list is bounded. */
    private static final int MAX_SAMPLED_EVENTS = 10;

    /**
     * Bound on an error body echoed into the log. A provider's failure page can be a whole HTML
     * document, and the first few lines of it say everything the first few thousand do. A
     * successful body is logged in full instead: it is the evidence the rest of the run is built
     * on, and reading half of it answers nothing.
     */
    private static final int MAX_LOGGED_ERROR_CHARACTERS = 4_000;

    private static final LogEvidence NO_LOGS = new LogEvidence(0, null, List.of(), null);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final String query;
    private final URI searchUrl;

    HttpSplunkCollector(RestClient.Builder builder, AgentProperties properties) {
        AgentProperties.Splunk splunk = properties.splunk();
        this.restClient = builder
            // A configured trailing slash would otherwise yield '...//services/search/jobs/export'.
            .baseUrl(splunk.baseUrl().replaceAll("/+$", ""))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + splunk.token())
            .build();
        this.query = splunk.query();
        this.searchUrl = splunk.searchLink() == null || splunk.searchLink().isBlank()
            ? null
            : URI.create(splunk.searchLink());
    }

    @Override
    public LogEvidence collect(IncidentAlert alert) {
        List<JsonNode> errors = errorEvents(fetch(alert));
        if (errors.isEmpty()) {
            log.warn("Splunk returned no ERROR events for incident {}", alert.incidentId());
            return NO_LOGS;
        }
        log.info("Collected {} ERROR events for incident {}", errors.size(), alert.incidentId());
        return new LogEvidence(errors.size(), topError(errors), samples(errors), this.searchUrl);
    }

    private List<JsonNode> fetch(IncidentAlert alert) {
        try {
            String body = this.restClient.post()
                .uri(EXPORT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(searchRequest(alert))
                .retrieve()
                .body(String.class);
            log.info("Splunk {} returned {} characters for incident {}: {}", EXPORT_PATH,
                body == null ? 0 : body.length(), alert.incidentId(),
                body == null ? "<none>" : body);
            return results(body);
        }
        catch (RestClientException | JacksonException exception) {
            log.warn("Splunk search failed for incident {}; collecting no logs. Response body: {}",
                alert.incidentId(), truncate(errorBody(exception)), exception);
            return List.of();
        }
    }

    /**
     * Times are sent as epoch seconds rather than a formatted timestamp: Splunk reads a bare number
     * as absolute UTC, and any other form is interpreted in the search head's own time zone.
     */
    private MultiValueMap<String, String> searchRequest(IncidentAlert alert) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("search", search(alert));
        form.add("earliest_time", String.valueOf(alert.triggeredAt().minus(LOOKBACK).getEpochSecond()));
        form.add("latest_time", String.valueOf(alert.triggeredAt().plus(LOOKAHEAD).getEpochSecond()));
        form.add("output_mode", "json");
        form.add("count", String.valueOf(MAX_EXPORTED_EVENTS));
        return form;
    }

    /**
     * Splunk rejects a search that does not open with a generating command, and the configured
     * query is written as a bare filter often enough to be worth prefixing here rather than in
     * every environment's configuration.
     */
    private String search(IncidentAlert alert) {
        String configured = this.query == null ? "" : this.query.trim();
        String base = configured.startsWith("search ") || configured.startsWith("|")
            ? configured
            : "search " + configured;
        return base + " service=\"" + alert.serviceName() + "\"";
    }

    /**
     * The error body, which {@code retrieve()} raises as an exception rather than returning. Splunk
     * answers a bad token with 401 and a {@code messages} array explaining it, and without this
     * that explanation is lost.
     */
    private static String errorBody(Exception exception) {
        return exception instanceof HttpStatusCodeException status
            ? status.getStatusCode() + " " + status.getResponseBodyAsString()
            : null;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<none>";
        }
        return body.length() <= MAX_LOGGED_ERROR_CHARACTERS
            ? body
            : body.substring(0, MAX_LOGGED_ERROR_CHARACTERS) + "... (" + body.length() + " total)";
    }

    /**
     * One event per line. A malformed line is skipped rather than failing the search: export
     * streams, so a truncated connection leaves a half-written last line behind otherwise-good
     * results.
     */
    private List<JsonNode> results(String body) {
        List<JsonNode> events = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return events;
        }
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = this.objectMapper.readTree(line);
            }
            catch (JacksonException exception) {
                log.warn("Skipping unparseable Splunk export line: {}", truncate(line));
                continue;
            }
            if (!node.path("preview").asBoolean(false) && node.path("result").isObject()) {
                events.add(node.get("result"));
            }
        }
        return events;
    }

    private static List<JsonNode> errorEvents(List<JsonNode> events) {
        return events.stream()
            .filter(event -> ERROR_LEVEL.equalsIgnoreCase(event.path("level").asString("")))
            .toList();
    }

    /**
     * Events carrying an exception win over plain log lines. The exception signature is the string
     * {@code EvidenceClassifier} matches its application-error markers against, and an aspect's
     * request line carries no exception type at all.
     */
    private static String topError(List<JsonNode> errors) {
        List<JsonNode> withException = errors.stream()
            .filter(HttpSplunkCollector::hasException)
            .toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        (withException.isEmpty() ? errors : withException)
            .forEach(event -> counts.merge(signature(event), 1L, Long::sum));

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /** Events are sampled in the order Splunk returned them, which is newest first. */
    private static List<String> samples(List<JsonNode> errors) {
        List<String> lines = new ArrayList<>();
        errors.stream().limit(MAX_SAMPLED_EVENTS).forEach(event -> {
            lines.add("%s %s [%s] %s".formatted(
                field(event, "timestamp", "_time"),
                event.path("level").asString(""),
                field(event, "logger", "logger_name"),
                field(event, "message", "_raw")));

            if (hasException(event)) {
                lines.add(signature(event));
                frame(lines, field(event, "exception.origin", "exception_origin"));
                frame(lines, field(event, "exception.entryPoint", "exception_entryPoint"));
            }
        });
        return List.copyOf(lines);
    }

    private static void frame(List<String> lines, String frame) {
        if (!frame.isBlank()) {
            lines.add("\tat " + frame);
        }
    }

    private static boolean hasException(JsonNode event) {
        return !field(event, "exception.type", "exception_type").isBlank();
    }

    private static String signature(JsonNode event) {
        return hasException(event)
            ? field(event, "exception.type", "exception_type") + ": "
                + field(event, "exception.message", "exception_message")
            : field(event, "message", "_raw");
    }

    /**
     * A Splunk result is flat, and which name a nested field lands under depends on how the index
     * extracts it — dotted for {@code spath}, underscored for a props.conf extraction — so both
     * spellings are read.
     */
    private static String field(JsonNode event, String name, String alternative) {
        String value = event.path(name).asString("");
        return value.isBlank() ? event.path(alternative).asString("") : value;
    }
}
