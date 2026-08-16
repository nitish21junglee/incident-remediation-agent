package com.hackathon.incident_remediation_agent.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PagerDutyPayloadParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PagerDutyPayloadParser parser = new PagerDutyPayloadParser();

    @Test
    void parsesTriggeredIncident() {
        IncidentAlert alert = parser.parse(fixture());

        assertThat(alert.eventId()).isEqualTo("01JDEMOEVENT");
        assertThat(alert.incidentId()).isEqualTo("PINCIDENT");
        assertThat(alert.title()).isEqualTo("demo-api error rate increased");
        assertThat(alert.serviceId()).isEqualTo("PDEMO");
        assertThat(alert.triggeredAt()).isEqualTo(Instant.parse("2026-08-14T02:14:00Z"));
        assertThat(alert.incidentUrl())
            .isEqualTo(URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    @Test
    void rejectsUnsupportedEventType() {
        JsonNode payload = fixture();
        ((ObjectNode) payload.at("/event")).put("event_type", "incident.resolved");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> parser.parse(payload))
            .withMessageContaining("incident.triggered");
    }

    @Test
    void rejectsBlankIncidentId() {
        JsonNode payload = fixture();
        ((ObjectNode) payload.at("/event/data")).put("id", "  ");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> parser.parse(payload))
            .withMessageContaining("/event/data/id");
    }

    @Test
    void rejectsMissingServiceId() {
        JsonNode payload = fixture();
        ((ObjectNode) payload.at("/event/data")).remove("service");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> parser.parse(payload))
            .withMessageContaining("/event/data/service/id");
    }

    private JsonNode fixture() {
        try (InputStream stream = getClass().getResourceAsStream("/pagerduty-incident-triggered.json")) {
            return objectMapper.readTree(stream);
        }
        catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read webhook fixture", exception);
        }
    }
}
