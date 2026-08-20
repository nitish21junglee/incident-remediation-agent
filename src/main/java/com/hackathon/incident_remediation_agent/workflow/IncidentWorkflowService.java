package com.hackathon.incident_remediation_agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.jira.JiraClient;
import com.hackathon.incident_remediation_agent.jira.JiraTicket;

/**
 * Runs the incident investigation off the webhook thread.
 *
 * <p>Jira is created before any other work. A Jira failure abandons the run, so no evidence is
 * collected and no AI job or pull request is created.
 *
 * <p>Evidence collection and AI routing are added once the collectors exist.
 */
@Service
public class IncidentWorkflowService implements IncidentWorkflow {

    private static final Logger log = LoggerFactory.getLogger(IncidentWorkflowService.class);

    private final JiraClient jiraClient;
    private final IncidentRunStore store;

    IncidentWorkflowService(JiraClient jiraClient, IncidentRunStore store) {
        this.jiraClient = jiraClient;
        this.store = store;
    }

    @Override
    @Async
    public void start(IncidentRun run) {
        IncidentAlert alert = run.alert();
        try {
            JiraTicket ticket = this.jiraClient.createIncident(alert);
            this.store.update(alert.incidentId(), current -> current.jiraCreated(ticket.key()));
            log.info("Created Jira ticket {} ({}) for incident {}",
                ticket.key(), ticket.browseUrl(), alert.incidentId());
        }
        catch (RuntimeException exception) {
            log.error("Jira ticket creation failed for incident {}; abandoning run",
                alert.incidentId(), exception);
            this.store.update(alert.incidentId(),
                current -> current.failed("jira ticket creation failed: " + exception.getMessage()));
        }
    }
}
