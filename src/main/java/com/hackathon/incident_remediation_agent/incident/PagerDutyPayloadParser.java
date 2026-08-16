package com.hackathon.incident_remediation_agent.incident;

import java.net.URI;
import java.time.Instant;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Reads the seven fields the workflow needs out of a PagerDuty v3 webhook envelope. Every other
 * field in a real payload is ignored on purpose, so PagerDuty adding fields cannot break this.
 */
@Component
public class PagerDutyPayloadParser {

    private static final String SUPPORTED_EVENT_TYPE = "incident.triggered";

    public IncidentAlert parse(JsonNode payload) {
        String eventType = required(payload, "/event/event_type");
        if (!SUPPORTED_EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException(
                "Unsupported PagerDuty event type '%s'; only %s is handled"
                    .formatted(eventType, SUPPORTED_EVENT_TYPE));
        }
        return new IncidentAlert(
            required(payload, "/event/id"),
            required(payload, "/event/data/id"),
            required(payload, "/event/data/title"),
            required(payload, "/event/data/service/id"),
            Instant.parse(required(payload, "/event/occurred_at")),
            URI.create(required(payload, "/event/data/html_url")));
    }

    private static String required(JsonNode payload, String pointer) {
        JsonNode node = payload.at(pointer);
        String value = node.isMissingNode() || node.isNull() ? "" : node.asString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required PagerDuty field " + pointer);
        }
        return value;
    }
}
