package com.hackathon.incident_remediation_agent.jira;

import org.springframework.stereotype.Component;

import com.hackathon.incident_remediation_agent.ai.FixProposal;
import com.hackathon.incident_remediation_agent.evidence.EvidencePack;
import com.hackathon.incident_remediation_agent.git.DraftPullRequest;
import com.hackathon.incident_remediation_agent.git.PatchValidationException;
import com.hackathon.incident_remediation_agent.git.WorkspaceResult;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;

/**
 * Builds Atlassian Document Format payloads. Every method must be implemented with Jackson
 * {@code ObjectNode}/{@code ArrayNode}; never concatenate JSON strings.
 *
 * <p>Contract stub only — bodies are filled in by Tasks 3, 6 and 9.
 */
@Component
public class JiraDocumentFactory {

    public JsonNode initialDescription(IncidentAlert alert) {
        throw new UnsupportedOperationException("Task 3: implement initialDescription");
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
}
