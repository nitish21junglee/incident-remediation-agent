package com.hackathon.incident_remediation_agent.jira;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.DeploymentEvidence;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.git.DraftPullRequest;
import com.hackathon.incident_remediation_agent.git.FixSubmission;
import com.hackathon.incident_remediation_agent.git.PatchValidationException;
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

    /** The evidence comment is the second hard gate: both signals must actually reach Jira. */
    @Test
    void contextCommentCarriesSplunkAndSignalFxEvidence() {
        String text = flattenText(factory.contextComment(pack("application_error")));

        assertThat(text)
            .contains("Classification: ", "application_error")
            .contains("Evidence version: ", "evidence-sha")
            .contains("Matching log events: ", "184")
            .contains("Top error: ", "NullPointerException")
            .contains("Error rate before: ", "0.001")
            .contains("Error rate during: ", "0.4")
            .contains("Latency changed: ", "true");
    }

    @Test
    void contextCommentLinksToBothEvidenceSources() {
        assertThat(hrefs(factory.contextComment(pack("application_error"))))
            .contains("https://splunk.example/app/search", "https://signalfx.example/dashboard");
    }

    /** Stack traces belong in a code block, not a paragraph, or Jira renders them unreadably. */
    @Test
    void contextCommentPutsLogSamplesInACodeBlock() {
        JsonNode document = factory.contextComment(pack("application_error"));

        assertThat(blockTypes(document)).contains("codeBlock");
        assertThat(flattenText(document)).contains("KafkaConsumer.java:142");
    }

    @Test
    void noCodeActionKeepsTheEvidenceAndExplainsWhyNothingWasChanged() {
        String text = flattenText(factory.noCodeAction(pack("infrastructure_or_dependency")));

        assertThat(text)
            .contains("Error rate during: ")
            .contains("No code investigation started")
            .contains("infrastructure_or_dependency");
    }

    @Test
    void finalPrMessageLinksTheDraftPullRequestAlongsideTheEvidence() {
        JsonNode document = factory.finalPrMessage(pack("application_error"), proposal(),
            new FixSubmission("agent/incident/SCRUM-1-guard", "commit-sha", List.of(SOURCE),
                new DraftPullRequest(73, URI.create("https://github.com/x/y/pull/73"))));

        assertThat(flattenText(document))
            .contains("Top error: ")
            .contains("RewardEvent.getPayload() can be null")
            .contains("Branch: ", "agent/incident/SCRUM-1-guard")
            .contains("Commit: ", "commit-sha")
            .contains(SOURCE)
            .contains("Review draft pull request #73");
        assertThat(hrefs(document)).contains("https://github.com/x/y/pull/73");
    }

    /** A dry run must say so, not imply a branch exists. */
    @Test
    void finalPrMessageSaysNothingWasWrittenWhenPushingIsDisabled() {
        JsonNode document = factory.finalPrMessage(pack("application_error"), proposal(),
            new FixSubmission("agent/incident/SCRUM-1-guard", null, List.of(SOURCE), null));

        assertThat(flattenText(document))
            .contains("Nothing was written to GitHub")
            .doesNotContain("Review draft pull request");
        assertThat(hrefs(document)).doesNotContain("https://github.com/x/y/pull/73");
    }

    @Test
    void validationFailureMessageRecordsTheHypothesisAndTheReason() {
        String text = flattenText(factory.validationFailureMessage(proposal(),
            new PatchValidationException("Proposal writes to protected path .github/workflows/ci.yaml")));

        assertThat(text)
            .contains("Proposed fix rejected")
            .contains("RewardEvent.getPayload() can be null")
            .contains("protected path .github/workflows/ci.yaml")
            .contains("No branch or pull request was created");
    }

    /** Jira rejects unknown block types outright, so every document is checked structurally. */
    @Test
    void everyDocumentUsesOnlySupportedBlockTypes() {
        List<JsonNode> documents = List.of(
            factory.contextComment(pack("application_error")),
            factory.noCodeAction(pack("unknown")),
            factory.finalPrMessage(pack("application_error"), proposal(),
                new FixSubmission("b", "c", List.of(SOURCE), null)),
            factory.validationFailureMessage(proposal(), new PatchValidationException("nope")));

        assertThat(documents).allSatisfy(document -> {
            assertThat(document.get("version").asInt()).isEqualTo(1);
            assertThat(document.get("type").asString()).isEqualTo("doc");
            assertThat(blockTypes(document))
                .isSubsetOf("paragraph", "bulletList", "heading", "codeBlock");
        });
    }

    private static final String SOURCE =
        "src/main/java/com/flutter/reward_service/service/KafkaConsumer.java";

    private static FixProposal proposal() {
        return new FixProposal(true, "RewardEvent.getPayload() can be null",
            "Guard the null reward payload", Map.of(SOURCE, "class KafkaConsumer {}"));
    }

    private EvidencePack pack(String classification) {
        return new EvidencePack(alert,
            new JiraTicket("SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1")),
            new LogEvidence(184, "java.lang.NullPointerException: payload is null",
                List.of("java.lang.NullPointerException: payload is null",
                    "\tat com.flutter.reward_service.service.KafkaConsumer"
                        + ".consumeRewardEvent(KafkaConsumer.java:142)"),
                URI.create("https://splunk.example/app/search")),
            new MetricEvidence(0.001, 0.4, true, URI.create("https://signalfx.example/dashboard"),
                List.of()),
            new DeploymentEvidence("v1.4.2", "abc123", null,
                Instant.parse("2026-08-14T02:04:00Z")),
            null, classification, "evidence-sha");
    }

    private static List<String> blockTypes(JsonNode document) {
        return blocks(document).stream().map(block -> block.get("type").asString()).toList();
    }

    private static List<String> hrefs(JsonNode document) {
        return document.findValues("href").stream()
            .filter(JsonNode::isString)
            .map(JsonNode::asString)
            .toList();
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
