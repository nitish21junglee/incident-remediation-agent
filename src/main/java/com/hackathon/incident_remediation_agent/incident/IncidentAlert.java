package com.hackathon.incident_remediation_agent.incident;

import java.net.URI;
import java.time.Instant;

/**
 * @param serviceId   the PagerDuty service id, for example {@code PDEMO}
 * @param serviceName the human service name from the webhook, for example {@code demo-api}. This
 *                    is what Splunk and SignalFx queries key on, and what points at the owning
 *                    repository. Falls back to {@code serviceId} when the payload omits it.
 */
public record IncidentAlert(
    String eventId,
    String incidentId,
    String title,
    String serviceId,
    String serviceName,
    Instant triggeredAt,
    URI incidentUrl
) {}
