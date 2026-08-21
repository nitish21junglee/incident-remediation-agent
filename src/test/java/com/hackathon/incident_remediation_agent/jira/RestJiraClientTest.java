package com.hackathon.incident_remediation_agent.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.hackathon.incident_remediation_agent.config.AgentProperties;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class RestJiraClientTest {

    private static final String BASE = "https://example.atlassian.net";

    private static final String EXPECTED_AUTH = "Basic " + Base64.getEncoder()
        .encodeToString("demo@example.com:fixture-token".getBytes(StandardCharsets.UTF_8));

    private final IncidentAlert alert = new IncidentAlert(
        "01JDEMOEVENT",
        "PINCIDENT",
        "demo-api error rate increased",
        "PDEMO",
        "demo-api",
        Instant.parse("2026-08-14T02:14:00Z"),
        URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));

    private final JiraTicket ticket = new JiraTicket("HACK-42", URI.create(BASE + "/browse/HACK-42"));

    private MockRestServiceServer server;

    private RestJiraClient client;

    @BeforeEach
    void setUp() {
        givenBaseUrl(BASE);
    }

    @Test
    void createsIncidentWithAdfDescriptionAndLabels() {
        server.expect(requestTo(BASE + "/rest/api/3/issue"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_AUTH))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.fields.project.key").value("HACK"))
            .andExpect(jsonPath("$.fields.issuetype.name").value("Task"))
            .andExpect(jsonPath("$.fields.summary").value("demo-api error rate increased"))
            .andExpect(jsonPath("$.fields.description.version").value(1))
            .andExpect(jsonPath("$.fields.description.type").value("doc"))
            .andExpect(jsonPath("$.fields.labels").value(containsInAnyOrder("pagerduty", "ai-triage")))
            .andRespond(withSuccess(
                """
                {"id":"10042","key":"HACK-42","self":"https://example.atlassian.net/rest/api/3/issue/10042"}
                """,
                MediaType.APPLICATION_JSON));

        JiraTicket created = client.createIncident(alert);

        assertThat(created.key()).isEqualTo("HACK-42");
        assertThat(created.browseUrl()).isEqualTo(URI.create(BASE + "/browse/HACK-42"));
        server.verify();
    }

    /**
     * The create response's {@code self} is a REST URL, so the browse URL must come from the
     * configured base URL. A trailing slash there must not produce a double slash.
     */
    @Test
    void normalisesTrailingSlashInConfiguredBaseUrl() {
        givenBaseUrl(BASE + "/");
        server.expect(requestTo(BASE + "/rest/api/3/issue"))
            .andRespond(withSuccess("{\"id\":\"10042\",\"key\":\"HACK-42\"}", MediaType.APPLICATION_JSON));

        JiraTicket created = client.createIncident(alert);

        assertThat(created.browseUrl()).isEqualTo(URI.create(BASE + "/browse/HACK-42"));
        server.verify();
    }

    @Test
    void addsContextCommentAndReturnsItsId() {
        server.expect(requestTo(BASE + "/rest/api/3/issue/HACK-42/comment"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_AUTH))
            .andExpect(jsonPath("$.body.type").value("doc"))
            .andExpect(jsonPath("$.body.content[0].content[0].text").value("evidence"))
            .andRespond(withSuccess("{\"id\":\"20001\"}", MediaType.APPLICATION_JSON));

        String commentId = client.addComment(ticket, comment("evidence"));

        assertThat(commentId).isEqualTo("20001");
        server.verify();
    }

    @Test
    void updatesTheSameManagedComment() {
        server.expect(requestTo(BASE + "/rest/api/3/issue/HACK-42/comment/20001"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, EXPECTED_AUTH))
            .andExpect(jsonPath("$.body.content[0].content[0].text").value("draft PR ready"))
            .andRespond(withSuccess("{\"id\":\"20001\"}", MediaType.APPLICATION_JSON));

        client.updateComment(ticket, "20001", comment("draft PR ready"));

        server.verify();
    }

    private void givenBaseUrl(String baseUrl) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new RestJiraClient(builder, properties(baseUrl), new JiraDocumentFactory());
    }

    private static AgentProperties properties(String baseUrl) {
        return new AgentProperties(
            "fixture",
            "PDEMO",
            null,
            new AgentProperties.Jira(baseUrl, "demo@example.com", "fixture-token", "HACK", "Task"),
            null,
            null,
            null,
            null,
            null);
    }

    /** Minimal ADF stand-in; the real documents come from {@link JiraDocumentFactory}. */
    private static ObjectNode comment(String message) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode document = nodes.objectNode();
        document.put("version", 1);
        document.put("type", "doc");
        ObjectNode paragraph = document.putArray("content").addObject();
        paragraph.put("type", "paragraph");
        ObjectNode text = paragraph.putArray("content").addObject();
        text.put("type", "text");
        text.put("text", message);
        return document;
    }
}
