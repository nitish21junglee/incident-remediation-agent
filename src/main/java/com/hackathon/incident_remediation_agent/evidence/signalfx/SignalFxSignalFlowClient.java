package com.hackathon.incident_remediation_agent.evidence.signalfx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Executes SignalFlow programs against the SignalFx streaming API and returns the resulting
 * data points as a JSON array.
 *
 * <p>The API streams Server-Sent Events of several kinds ({@code control-message}, {@code
 * metadata}, {@code data}, {@code message}). Only {@code metadata} (which maps a time series id
 * to its dimensions) and {@code data} (the actual points) carry information relevant to
 * evidence collection; the rest are discarded. Each returned element merges a data point's
 * {@code timestampMs}/{@code value} with the dimensions of its time series, e.g.:
 *
 * <pre>{@code
 * [
 *   {"timestampMs": 1786977240000, "value": 1368.77, "label": "C", "uri": "/v3/search/brands"},
 *   ...
 * ]
 * }</pre>
 *
 * <p>Instances are thread-safe and cheap to reuse across queries.
 */
public final class SignalFxSignalFlowClient {

    private static final String CONTROL_MESSAGE_EVENT = "control-message";
    private static final String METADATA_EVENT = "metadata";
    private static final String DATA_EVENT = "data";
    private static final String MESSAGE_EVENT = "message";
    private static final String DATA_PREFIX = "data:";
    private static final String STREAM_LABEL_PROPERTY = "sf_streamLabel";
    private static final String INTERNAL_PROPERTY_PREFIX = "sf_";
    private static final String COMPUTATION_ID_PROPERTY = "computationId";

    private final String token;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SignalFxSignalFlowClient(String token, String realm) {
        this.token = Objects.requireNonNull(token, "token");
        this.baseUrl = "https://api." + Objects.requireNonNull(realm, "realm") + ".signalfx.com";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Runs {@code program} for {@code service} over the window {@code [now - lookback, now]}
     * and returns the resolved data points as a JSON array string.
     */
    public String query(SignalFxProgram program, SignalFxService service, Duration lookback, Duration resolution) {
        return query(program.forService(service), lookback, resolution);
    }

    /**
     * Runs a raw {@code program} over the window {@code [now - lookback, now]} and returns the
     * resolved data points as a JSON array string. Prefer
     * {@link #query(SignalFxProgram, SignalFxService, Duration, Duration)} for the common cases;
     * use this overload for ad-hoc SignalFlow programs.
     *
     * @param program    a SignalFlow program, e.g. built with a
     *                   {@link SignalFxService#toFilterClause()} filter
     * @param lookback   how far back from now the query window starts
     * @param resolution the requested computation resolution
     */
    public String query(String program, Duration lookback, Duration resolution) {
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - lookback.toMillis();
        String queryString = "start=%d&stop=%d&resolution=%d&immediate=true"
            .formatted(startMs, nowMs, resolution.toMillis());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v2/signalflow/execute?" + queryString))
            .header("X-SF-TOKEN", token)
            .header("Content-Type", "text/plain")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(program))
            .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new SignalFxQueryException("Failed to call SignalFlow API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SignalFxQueryException("Interrupted while calling SignalFlow API", e);
        }

        if (response.statusCode() != 200) {
            throw new SignalFxQueryException("SignalFlow API returned status " + response.statusCode());
        }

        List<Map<String, Object>> points;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            points = parseEvents(reader);
        } catch (IOException e) {
            throw new SignalFxQueryException("Failed to read SignalFlow response stream", e);
        }

        try {
            return objectMapper.writeValueAsString(points);
        } catch (JsonProcessingException e) {
            throw new SignalFxQueryException("Failed to serialize SignalFlow data points", e);
        }
    }

    private List<Map<String, Object>> parseEvents(BufferedReader reader) throws IOException {
        Map<String, Map<String, Object>> dimensionsByTsId = new HashMap<>();
        List<Map<String, Object>> points = new ArrayList<>();

        String currentEvent = null;
        List<String> dataLines = new ArrayList<>();

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                consumeEvent(currentEvent, dataLines, dimensionsByTsId, points);
                currentEvent = null;
                dataLines.clear();
            } else if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith(DATA_PREFIX)) {
                dataLines.add(stripDataPrefix(line));
            }
            // any other SSE field (e.g. "id:") carries nothing we need
        }
        consumeEvent(currentEvent, dataLines, dimensionsByTsId, points);

        return points;
    }

    private void consumeEvent(
            String event,
            List<String> dataLines,
            Map<String, Map<String, Object>> dimensionsByTsId,
            List<Map<String, Object>> points) throws IOException {
        if (event == null || dataLines.isEmpty()) {
            return;
        }

        JsonNode payload = objectMapper.readTree(String.join("\n", dataLines));

        switch (event) {
            case METADATA_EVENT -> recordDimensions(payload, dimensionsByTsId);
            case DATA_EVENT -> collectDataPoints(payload, dimensionsByTsId, points);
            case CONTROL_MESSAGE_EVENT, MESSAGE_EVENT -> {
                // job lifecycle / informational noise, not needed for evidence collection
            }
            default -> {
                // unknown event types are ignored defensively
            }
        }
    }

    private static void recordDimensions(JsonNode payload, Map<String, Map<String, Object>> dimensionsByTsId) {
        String tsId = payload.path("tsId").asText(null);
        if (tsId == null) {
            return;
        }

        Map<String, Object> dimensions = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> properties = payload.path("properties").fields();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            String key = property.getKey();
            if (key.equals(STREAM_LABEL_PROPERTY)) {
                dimensions.put("label", property.getValue().asText());
            } else if (!key.startsWith(INTERNAL_PROPERTY_PREFIX) && !key.equals(COMPUTATION_ID_PROPERTY)) {
                dimensions.put(key, jsonNodeToJavaValue(property.getValue()));
            }
        }
        dimensionsByTsId.put(tsId, dimensions);
    }

    private static void collectDataPoints(
            JsonNode payload,
            Map<String, Map<String, Object>> dimensionsByTsId,
            List<Map<String, Object>> points) {
        long timestampMs = payload.path("logicalTimestampMs").asLong();

        for (JsonNode entry : payload.path("data")) {
            String tsId = entry.path("tsId").asText(null);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestampMs", timestampMs);
            point.put("value", entry.path("value").asDouble());
            point.putAll(dimensionsByTsId.getOrDefault(tsId, Map.of()));
            points.add(point);
        }
    }

    private static String stripDataPrefix(String line) {
        String value = line.substring(DATA_PREFIX.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static Object jsonNodeToJavaValue(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
