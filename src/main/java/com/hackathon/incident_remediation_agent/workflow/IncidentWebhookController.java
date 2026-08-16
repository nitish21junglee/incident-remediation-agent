package com.hackathon.incident_remediation_agent.workflow;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;
import com.hackathon.incident_remediation_agent.incident.IncidentRun;
import com.hackathon.incident_remediation_agent.incident.IncidentRunStore;
import com.hackathon.incident_remediation_agent.incident.PagerDutyPayloadParser;
import com.hackathon.incident_remediation_agent.incident.WorkflowStage;

import tools.jackson.databind.JsonNode;

@RestController
public class IncidentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(IncidentWebhookController.class);

    private final PagerDutyPayloadParser parser;
    private final IncidentRunStore store;
    private final IncidentWorkflow workflow;

    IncidentWebhookController(PagerDutyPayloadParser parser, IncidentRunStore store, IncidentWorkflow workflow) {
        this.parser = parser;
        this.store = store;
        this.workflow = workflow;
    }

    @PostMapping("/webhooks/pagerduty")
    ResponseEntity<Map<String, Object>> receive(@RequestBody JsonNode payload) {
        log.info("PagerDuty webhook received: {}", payload);
        IncidentAlert alert = parser.parse(payload);

        Optional<IncidentRun> started = store.start(alert);
        started.ifPresent(workflow::start);

        WorkflowStage stage = started.or(() -> store.get(alert.incidentId()))
            .map(IncidentRun::stage)
            .orElse(WorkflowStage.RECEIVED);
        return ResponseEntity.accepted().body(Map.of(
            "incidentId", alert.incidentId(),
            "stage", stage.name(),
            "duplicate", started.isEmpty()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleUnsupportedPayload(IllegalArgumentException exception) {
        log.warn("Rejected PagerDuty payload: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}
