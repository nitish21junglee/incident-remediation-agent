package com.hackathon.incident_remediation_agent.evidence;

import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

public interface SplunkCollector {

    LogEvidence collect(IncidentAlert alert);
}
