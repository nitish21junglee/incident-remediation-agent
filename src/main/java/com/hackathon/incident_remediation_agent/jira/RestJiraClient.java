package com.hackathon.incident_remediation_agent.jira;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Jira Cloud REST v3 adapter. v3 is required because descriptions and comment bodies are Atlassian
 * Document Format; the v2 equivalents accept plain text only.
 *
 * <p>Failures propagate as {@code RestClientException}. Jira is the workflow's first hard gate, so
 * the caller — not this adapter — decides that a failed ticket means the run is abandoned.
 */
@Component
public class RestJiraClient implements JiraClient {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final List<String> LABELS = List.of("pagerduty", "ai-triage");

    private final RestClient restClient;
    private final JiraDocumentFactory documents;
    private final String browseBaseUrl;
    private final String projectKey;
    private final String issueType;

    RestJiraClient(RestClient.Builder builder, AgentProperties properties, JiraDocumentFactory documents) {
        AgentProperties.Jira jira = properties.jira();
        this.documents = documents;
        this.projectKey = jira.projectKey();
        this.issueType = jira.issueType();
        // A configured trailing slash would otherwise yield '.../browse//HACK-42'.
        this.browseBaseUrl = jira.baseUrl().replaceAll("/+$", "");
        this.restClient = builder
            .baseUrl(this.browseBaseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(jira.email(), jira.apiToken()))
            .build();
    }

    @Override
    public JiraTicket createIncident(IncidentAlert alert) {
        ObjectNode fields = NODES.objectNode();
        fields.putObject("project").put("key", this.projectKey);
        fields.putObject("issuetype").put("name", this.issueType);
        fields.put("summary", "[PagerDuty] " + alert.title());
        fields.set("description", this.documents.initialDescription(alert));
        ArrayNode labels = fields.putArray("labels");
        LABELS.forEach(labels::add);

        ObjectNode request = NODES.objectNode();
        request.set("fields", fields);

        JsonNode response = post("/rest/api/3/issue", request);
        String key = response.get("key").asString();
        return new JiraTicket(key, URI.create(this.browseBaseUrl + "/browse/" + key));
    }

    @Override
    public String addComment(JiraTicket ticket, JsonNode adfDocument) {
        JsonNode response = this.restClient.post()
            .uri("/rest/api/3/issue/{key}/comment", ticket.key())
            .contentType(MediaType.APPLICATION_JSON)
            .body(commentBody(adfDocument))
            .retrieve()
            .body(JsonNode.class);
        return response.get("id").asString();
    }

    @Override
    public void updateComment(JiraTicket ticket, String commentId, JsonNode adfDocument) {
        this.restClient.put()
            .uri("/rest/api/3/issue/{key}/comment/{commentId}", ticket.key(), commentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(commentBody(adfDocument))
            .retrieve()
            .toBodilessEntity();
    }

    private JsonNode post(String path, ObjectNode request) {
        return this.restClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(JsonNode.class);
    }

    private static ObjectNode commentBody(JsonNode adfDocument) {
        ObjectNode request = NODES.objectNode();
        request.set("body", adfDocument);
        return request;
    }

    /** Jira Cloud rejects passwords here; the token is the credential. */
    private static String basicAuth(String email, String apiToken) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }
}
