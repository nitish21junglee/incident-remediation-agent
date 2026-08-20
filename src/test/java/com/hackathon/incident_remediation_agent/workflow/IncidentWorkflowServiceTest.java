package com.hackathon.incident_remediation_agent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;
import com.hackathon.incident_remediation_agent.jira.JiraClient;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

import tools.jackson.databind.JsonNode;

class IncidentWorkflowServiceTest {

    private final IncidentAlert alert = new IncidentAlert(
        "01JDEMOEVENT",
        "PINCIDENT",
        "demo-api error rate increased",
        "PDEMO",
        "demo-api",
        Instant.parse("2026-08-14T02:14:00Z"),
        URI.create("https://example.pagerduty.com/incidents/PINCIDENT"));

    private final JiraTicket ticket = new JiraTicket(
        "SCRUM-1", URI.create("https://demo.atlassian.net/browse/SCRUM-1"));

    private JiraClient jiraClient;

    private IncidentRunStore store;

    private IncidentWorkflowService workflow;

    @BeforeEach
    void setUp() {
        this.jiraClient = mock(JiraClient.class);
        this.store = new IncidentRunStore();
        this.workflow = new IncidentWorkflowService(this.jiraClient, this.store);
        this.store.start(this.alert);
    }

    @Test
    void createsJiraTicketAndRecordsItsKey() {
        when(this.jiraClient.createIncident(this.alert)).thenReturn(this.ticket);

        this.workflow.start(storedRun());

        verify(this.jiraClient).createIncident(this.alert);
        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.JIRA_CREATED);
        assertThat(stored.jiraKey()).isEqualTo("SCRUM-1");
        assertThat(stored.alert()).isEqualTo(this.alert);
    }

    /**
     * Jira is the workflow's first hard gate: a failure there must abandon the run rather than
     * continue to evidence collection or AI.
     */
    @Test
    void marksRunFailedWhenJiraRejectsTheTicket() {
        when(this.jiraClient.createIncident(this.alert))
            .thenThrow(new IllegalStateException("403 Forbidden"));

        this.workflow.start(storedRun());

        IncidentRun stored = storedRun();
        assertThat(stored.stage()).isEqualTo(WorkflowStage.FAILED);
        assertThat(stored.message()).contains("403 Forbidden");
        assertThat(stored.jiraKey()).isNull();
        verify(this.jiraClient, never()).addComment(any(JiraTicket.class), any(JsonNode.class));
    }

    /** The webhook returns 202 immediately, so no external call may happen on its thread. */
    @Test
    void startIsAsync() throws Exception {
        assertThat(IncidentWorkflowService.class.getDeclaredMethod("start", IncidentRun.class))
            .matches(method -> method.isAnnotationPresent(Async.class));
    }

    private IncidentRun storedRun() {
        return this.store.get("PINCIDENT").orElseThrow();
    }
}
