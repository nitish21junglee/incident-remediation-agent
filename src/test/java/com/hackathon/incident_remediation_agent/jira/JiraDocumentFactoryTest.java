package com.hackathon.incident_remediation_agent.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;

class JiraDocumentFactoryTest {

    private final JiraDocumentFactory factory = new JiraDocumentFactory();

    private final IncidentAlert alert = new IncidentAlert(
        "01JDEMOEVENT",
        "PINCIDENT",
        "demo-api error rate increased",
        "PDEMO",
        Instant.parse("2026-08-14T02:14:00Z"),
        URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));

    @Test
    void initialDescriptionUsesAdfRoot() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(document.get("version").asInt()).isEqualTo(1);
        assertThat(document.get("type").asString()).isEqualTo("doc");
        assertThat(document.get("content").isArray()).isTrue();
        assertThat(document.get("content").isEmpty()).isFalse();
    }

    @Test
    void initialDescriptionStatesIncidentServiceAndTriggerTime() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(flattenText(document))
            .contains("PINCIDENT")
            .contains("PDEMO")
            .contains("2026-08-14T02:14:00Z");
    }

    @Test
    void initialDescriptionLinksToThePagerDutyIncident() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(document.findValues("attrs"))
            .anySatisfy(attrs -> assertThat(attrs.get("href").asString())
                .isEqualTo("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    @Test
    void initialDescriptionBuildsOnlyParagraphsOfTextNodes() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(document.get("content"))
            .allSatisfy(block -> assertThat(block.get("type").asString()).isEqualTo("paragraph"));
        assertThat(document.get("content"))
            .allSatisfy(block -> assertThat(block.get("content"))
                .allSatisfy(inline -> assertThat(inline.get("type").asString()).isEqualTo("text")));
    }

    /** Concatenates every ADF text node so assertions do not depend on paragraph layout. */
    private static String flattenText(JsonNode document) {
        return document.findValues("text").stream()
            .filter(JsonNode::isString)
            .map(JsonNode::asString)
            .collect(Collectors.joining(" "));
    }
}
