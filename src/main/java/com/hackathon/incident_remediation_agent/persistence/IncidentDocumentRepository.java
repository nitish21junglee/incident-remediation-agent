package com.hackathon.incident_remediation_agent.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentDocumentRepository extends MongoRepository<IncidentDocument, String> {
}
