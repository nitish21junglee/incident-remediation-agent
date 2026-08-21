package com.hackathon.incident_remediation_agent.jira;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.evidence.LogEvidence;
import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.git.FixSubmission;
import com.hackathon.incident_remediation_agent.git.PatchValidationException;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds Atlassian Document Format payloads with Jackson nodes. Never concatenate JSON strings here.
 *
 * <p>There is one managed comment per incident, rewritten in place as the investigation progresses,
 * so the ticket shows a current answer rather than a transcript.
 */
@Component
public class JiraDocumentFactory {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Jira rejects very large documents; log samples are the only unbounded input. */
    private static final int MAX_SAMPLE_CHARACTERS = 4_000;

    public JsonNode initialDescription(IncidentAlert alert) {
        ObjectNode document = document();
        ArrayNode blocks = (ArrayNode) document.get("content");

        paragraph(blocks).add(text("Created automatically from a PagerDuty incident."));

        ArrayNode facts = bulletList(blocks);
        fact(facts, "Incident", alert.incidentId());
        fact(facts, "Title", alert.title());
        fact(facts, "Service", "%s (%s)".formatted(alert.serviceName(), alert.serviceId()));
        fact(facts, "Triggered", alert.triggeredAt().toString());
        fact(facts, "Event", alert.eventId());

        paragraph(blocks).add(link("View incident in PagerDuty", alert.incidentUrl()));

        return document;
    }

    /** The correlated evidence, published before any model call. */
    public JsonNode contextComment(EvidencePack pack) {
        ObjectNode document = document();
        ArrayNode blocks = (ArrayNode) document.get("content");

        heading(blocks, "Incident context");
        evidenceFacts(blocks, pack);
        logSamples(blocks, pack.logs());
        paragraph(blocks).add(text("Investigating whether a code change can address this."));

        return document;
    }

    /** Terminal message when the evidence does not justify touching code. */
    public JsonNode noCodeAction(EvidencePack pack) {
        ObjectNode document = document();
        ArrayNode blocks = (ArrayNode) document.get("content");

        heading(blocks, "Incident context");
        evidenceFacts(blocks, pack);
        logSamples(blocks, pack.logs());

        heading(blocks, "No code change proposed");
        paragraph(blocks).add(text(
            "The evidence was investigated and classified as '%s', but no code change came out of "
                .formatted(pack.classification())
                + "it: either nothing in the logs pointed at a file this agent may change, or the "
                + "model declined to propose one. This needs a human."));

        return document;
    }

    /** Terminal message when a fix was produced, whether or not it reached GitHub. */
    public JsonNode finalPrMessage(EvidencePack pack, FixProposal proposal, FixSubmission submission) {
        ObjectNode document = document();
        ArrayNode blocks = (ArrayNode) document.get("content");

        heading(blocks, "Incident context");
        evidenceFacts(blocks, pack);
        logSamples(blocks, pack.logs());

        heading(blocks, "Hypothesis");
        paragraph(blocks).add(text(proposal.hypothesis()));

        heading(blocks, "Proposed change");
        ArrayNode facts = bulletList(blocks);
        fact(facts, "Summary", proposal.summary());
        fact(facts, "Branch", submission.branch());
        if (submission.commitSha() != null) {
            fact(facts, "Commit", submission.commitSha());
        }
        submission.changedFiles().forEach(file -> fact(facts, "Changed file", file));

        if (submission.pushed()) {
            paragraph(blocks).add(link(
                "Review draft pull request #" + submission.pullRequest().number(),
                submission.pullRequest().url()));
            paragraph(blocks).add(text(
                "The branch was written by an agent and has not been compiled or tested."));
        }
        else {
            paragraph(blocks).add(text(
                "Nothing was written to GitHub: agent.github.push-enabled is false. The change "
                    + "above is what would have been pushed."));
        }

        return document;
    }

    /** Terminal message when a proposal existed but failed a gate. */
    public JsonNode validationFailureMessage(FixProposal proposal, PatchValidationException exception) {
        ObjectNode document = document();
        ArrayNode blocks = (ArrayNode) document.get("content");

        heading(blocks, "Proposed fix rejected");
        ArrayNode facts = bulletList(blocks);
        fact(facts, "Hypothesis", proposal.hypothesis());
        fact(facts, "Summary", proposal.summary());
        fact(facts, "Reason", exception.getMessage());

        paragraph(blocks).add(text(
            "No branch or pull request was created. The hypothesis above may still be useful."));

        return document;
    }

    private static void evidenceFacts(ArrayNode blocks, EvidencePack pack) {
        ArrayNode facts = bulletList(blocks);
        fact(facts, "Classification", pack.classification());
        fact(facts, "Evidence version", pack.evidenceVersion());

        LogEvidence logs = pack.logs();
        if (logs != null) {
            fact(facts, "Matching log events", String.valueOf(logs.errorCount()));
            fact(facts, "Top error", logs.topError());
        }
        MetricEvidence metrics = pack.metrics();
        if (metrics != null) {
            fact(facts, "Error rate before", String.valueOf(metrics.errorRateBefore()));
            fact(facts, "Error rate during", String.valueOf(metrics.errorRateDuring()));
            fact(facts, "Latency changed", String.valueOf(metrics.latencyChanged()));
        }
        if (pack.deployment() != null && pack.deployment().version() != null) {
            fact(facts, "Deployed version", pack.deployment().version());
        }

        if (logs != null && logs.sourceUrl() != null) {
            paragraph(blocks).add(link("Open the Splunk search", logs.sourceUrl()));
        }
        if (metrics != null && metrics.dashboardUrl() != null) {
            paragraph(blocks).add(link("Open the SignalFx dashboard", metrics.dashboardUrl()));
        }
    }

    private static void logSamples(ArrayNode blocks, LogEvidence logs) {
        if (logs == null || logs.samples() == null || logs.samples().isEmpty()) {
            return;
        }
        codeBlock(blocks, truncate(String.join("\n", logs.samples())));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_SAMPLE_CHARACTERS
            ? value
            : value.substring(0, MAX_SAMPLE_CHARACTERS);
    }

    private static ObjectNode document() {
        ObjectNode document = NODES.objectNode();
        document.put("version", 1);
        document.put("type", "doc");
        document.putArray("content");
        return document;
    }

    private static void heading(ArrayNode blocks, String value) {
        ObjectNode heading = blocks.addObject();
        heading.put("type", "heading");
        heading.putObject("attrs").put("level", 3);
        heading.putArray("content").add(text(value));
    }

    private static void codeBlock(ArrayNode blocks, String value) {
        ObjectNode block = blocks.addObject();
        block.put("type", "codeBlock");
        block.putArray("content").add(text(value));
    }

    /** Appends an empty paragraph block and returns its inline content array. */
    private static ArrayNode paragraph(ArrayNode blocks) {
        ObjectNode paragraph = blocks.addObject();
        paragraph.put("type", "paragraph");
        return paragraph.putArray("content");
    }

    private static ArrayNode bulletList(ArrayNode blocks) {
        ObjectNode list = blocks.addObject();
        list.put("type", "bulletList");
        return list.putArray("content");
    }

    /** Appends a "<label>: <value>" bullet with the label in bold. */
    private static void fact(ArrayNode items, String label, String value) {
        ObjectNode item = items.addObject();
        item.put("type", "listItem");
        ArrayNode inline = paragraph(item.putArray("content"));
        inline.add(strong(label + ": "));
        inline.add(text(value == null ? "unknown" : value));
    }

    private static ObjectNode strong(String value) {
        ObjectNode node = text(value);
        node.putArray("marks").addObject().put("type", "strong");
        return node;
    }

    private static ObjectNode text(String value) {
        ObjectNode node = NODES.objectNode();
        node.put("type", "text");
        node.put("text", value);
        return node;
    }

    private static ObjectNode link(String value, URI href) {
        ObjectNode node = text(value);
        ObjectNode mark = node.putArray("marks").addObject();
        mark.put("type", "link");
        mark.putObject("attrs").put("href", href.toString());
        return node;
    }
}
