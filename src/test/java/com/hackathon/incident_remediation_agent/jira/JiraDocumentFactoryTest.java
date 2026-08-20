package com.hackathon.incident_remediation_agent.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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
        "demo-api",
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

    /** Every field the webhook parser extracts must survive into the ticket. */
    @Test
    void initialDescriptionCarriesEveryIncidentField() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(flattenText(document))
            .contains("PINCIDENT")
            .contains("demo-api error rate increased")
            .contains("demo-api")
            .contains("PDEMO")
            .contains("2026-08-14T02:14:00Z")
            .contains("01JDEMOEVENT");
    }

    @Test
    void initialDescriptionLinksToThePagerDutyIncident() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(document.findValues("attrs"))
            .anySatisfy(attrs -> assertThat(attrs.get("href").asString())
                .isEqualTo("https://example.pagerduty.com/incidents/PINCIDENT"));
    }

    @Test
    void initialDescriptionListsIncidentFactsAsBullets() {
        JsonNode document = factory.initialDescription(alert);

        JsonNode bullets = blocks(document).stream()
            .filter(block -> "bulletList".equals(block.get("type").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no bulletList block"));

        assertThat(bullets.get("content")).hasSize(5);
        assertThat(bullets.get("content"))
            .allSatisfy(item -> assertThat(item.get("type").asString()).isEqualTo("listItem"));
        assertThat(flattenText(bullets))
            .contains("Incident: ")
            .contains("Service: ")
            .contains("Triggered: ")
            .contains("Event: ");
    }

    /** Jira silently drops blocks its editor does not recognise, so keep to the two we use. */
    @Test
    void initialDescriptionUsesOnlyParagraphAndBulletBlocks() {
        JsonNode document = factory.initialDescription(alert);

        assertThat(blocks(document))
            .allSatisfy(block -> assertThat(block.get("type").asString())
                .isIn("paragraph", "bulletList"));
    }

    private static List<JsonNode> blocks(JsonNode document) {
        return StreamSupport.stream(document.get("content").spliterator(), false).toList();
    }

    /** Concatenates every ADF text node so assertions do not depend on block layout. */
    private static String flattenText(JsonNode node) {
        return node.findValues("text").stream()
            .filter(JsonNode::isString)
            .map(JsonNode::asString)
            .collect(Collectors.joining(" "));
    }
}
