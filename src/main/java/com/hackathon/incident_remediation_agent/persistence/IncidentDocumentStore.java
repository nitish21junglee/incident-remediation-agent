package com.hackathon.incident_remediation_agent.persistence;

import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

/**
 * Mongo-backed counterpart to {@code IncidentRunStore}: persists the full incident record instead
 * of keeping it only in memory. Read-modify-write, since {@code save()} replaces the whole
 * document and there is no partial-field update here.
 */
@Component
public class IncidentDocumentStore {

    private final IncidentDocumentRepository repository;

    IncidentDocumentStore(IncidentDocumentRepository repository) {
        this.repository = repository;
    }

    public IncidentDocument start(String incidentId) {
        return this.repository.save(IncidentDocument.received(incidentId));
    }

    public Optional<IncidentDocument> get(String incidentId) {
        return this.repository.findById(incidentId);
    }

    /**
     * Atomically replaces the stored document. Returns the new value, or {@code null} when the
     * incident is unknown.
     */
    public IncidentDocument update(String incidentId, UnaryOperator<IncidentDocument> mutation) {
        return this.repository.findById(incidentId)
            .map(mutation)
            .map(this.repository::save)
            .orElse(null);
    }
}
