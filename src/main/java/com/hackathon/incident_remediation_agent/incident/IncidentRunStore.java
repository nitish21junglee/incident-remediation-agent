package com.hackathon.incident_remediation_agent.incident;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

@Component
public class IncidentRunStore {

    private final Map<String, IncidentRun> runs = new ConcurrentHashMap<>();

    /**
     * Registers a newly received alert. An empty result means the incident ID was already seen and
     * the caller must treat the webhook delivery as a duplicate.
     */
    public Optional<IncidentRun> start(IncidentAlert alert) {
        IncidentRun created = IncidentRun.received(alert);
        return runs.putIfAbsent(alert.incidentId(), created) == null
            ? Optional.of(created)
            : Optional.empty();
    }

    public Optional<IncidentRun> get(String incidentId) {
        return Optional.ofNullable(runs.get(incidentId));
    }

    /**
     * Atomically replaces the stored run. Returns the new value, or {@code null} when the incident
     * is unknown.
     */
    public IncidentRun update(String incidentId, UnaryOperator<IncidentRun> mutation) {
        return runs.computeIfPresent(incidentId, (id, current) -> mutation.apply(current));
    }
}
