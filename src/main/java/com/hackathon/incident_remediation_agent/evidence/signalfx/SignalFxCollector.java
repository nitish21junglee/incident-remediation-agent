package com.hackathon.incident_remediation_agent.evidence.signalfx;

import com.hackathon.incident_remediation_agent.evidence.MetricEvidence;
import com.hackathon.incident_remediation_agent.incident.IncidentAlert;

public interface SignalFxCollector {

    MetricEvidence collect(IncidentAlert alert);
}
