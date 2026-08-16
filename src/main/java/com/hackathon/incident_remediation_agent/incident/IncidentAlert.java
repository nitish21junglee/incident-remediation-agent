package com.hackathon.incident_remediation_agent.incident;

import java.net.URI;
import java.time.Instant;

public record IncidentAlert(
    String eventId,
    String incidentId,
    String title,
    String serviceId,
    Instant triggeredAt,
    URI incidentUrl
) {}
