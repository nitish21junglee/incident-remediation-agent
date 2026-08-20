package com.hackathon.incident_remediation_agent.jira;

import java.net.URI;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.git.DraftPullRequest;
import com.hackathon.incident_remediation_agent.git.PatchValidationException;
import com.hackathon.incident_remediation_agent.git.WorkspaceResult;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds Atlassian Document Format payloads. Every method must be implemented with Jackson
 * {@code ObjectNode}/{@code ArrayNode}; never concatenate JSON strings.
 *
 * <p>Contract stub only — bodies are filled in by Tasks 3, 6 and 9.
 */
@Component
public class JiraDocumentFactory {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public JsonNode initialDescription(IncidentAlert alert) {
        ObjectNode document = NODES.objectNode();
        document.put("version", 1);
        document.put("type", "doc");
        ArrayNode blocks = document.putArray("content");

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

    public JsonNode contextComment(EvidencePack pack) {
        throw new UnsupportedOperationException("Task 6 Step 5: implement contextComment");
    }

    public JsonNode noCodeAction(EvidencePack pack) {
        throw new UnsupportedOperationException("Task 9: implement noCodeAction");
    }

    public JsonNode finalPrMessage(
        EvidencePack pack,
        FixProposal proposal,
        WorkspaceResult workspace,
        DraftPullRequest pullRequest
    ) {
        throw new UnsupportedOperationException("Task 9: implement finalPrMessage");
    }

    public JsonNode validationFailureMessage(FixProposal proposal, PatchValidationException exception) {
        throw new UnsupportedOperationException("Task 9: implement validationFailureMessage");
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
        inline.add(text(value));
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
