package com.hackathon.incident_remediation_agent.jira;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

import tools.jackson.databind.JsonNode;

public interface JiraClient {

    JiraTicket createIncident(IncidentAlert alert);

    /**
     * @return the Jira comment ID of the single managed context comment
     */
    String addComment(JiraTicket ticket, JsonNode adfDocument);

    void updateComment(JiraTicket ticket, String commentId, JsonNode adfDocument);
}
